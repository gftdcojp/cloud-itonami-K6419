(ns banking.bankingadvisor
  "BankingOps-LLM client -- the *contained intelligence node* for the
  banking actor (README: \"Banking Advisor\").

  It normalizes account-intake, drafts a per-jurisdiction AML/KYC
  evidence checklist, screens accounts for an unresolved sanctions
  flag, drafts the settlement-posting action, and drafts the
  interbank-message-dispatch action. CRITICAL: it is a smart-but-
  untrusted advisor. It returns a *proposal* (with a rationale + the
  fields it cited), never a committed record or a real settlement
  posting/interbank message dispatch. Every output is censored
  downstream by `banking.governor` before anything touches the SSoT,
  and `:actuation/post-settlement`/`:actuation/dispatch-interbank-
  message` proposals NEVER auto-commit at any phase -- see README
  `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/post-settlement | :actuation/dispatch-interbank-message | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [banking.facts :as facts]
            [banking.registry :as registry]
            [banking.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the account, jurisdiction or IBAN. High confidence,
  low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "口座記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :account/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- verify-compliance
  "Per-jurisdiction AML/KYC evidence checklist draft. `:no-spec?`
  injects the failure mode we must defend against: proposing a
  checklist for a jurisdiction with NO official spec-basis in
  `banking.facts` -- the Monetary Intermediation Governor must reject
  this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [a (store/account db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction a))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "banking.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :compliance/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :compliance/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(def default-corporate-intel-screen
  "No-op corporate-intelligence cross-reference: always 'nothing on
  file'. This is the default so every existing caller of
  `screen-sanctions`/`infer`/`mock-advisor` keeps its exact prior
  behavior unless it explicitly wires in
  `banking.corporate-intel/screen` (or an equivalent). Not required
  from this namespace directly -- keeping the dependency optional at
  the bankingadvisor level, injected only by whoever builds the
  advisor."
  (constantly {:found? false :hit? false}))

(defn- screen-sanctions
  "Sanctions-screening draft. `:sanctions-flag-unresolved?` on the
  account record injects the failure mode: the Monetary Intermediation
  Governor must HOLD, un-overridably, on any unresolved flag.

  `screen-fn` (holder name -> corporate-intel result, see
  `banking.corporate-intel/screen`) is consulted ONLY once the local
  flag is otherwise clean -- it can turn a would-be :resolved into
  :unresolved, but a local unresolved flag is decided first, cheaply,
  without depending on an external actor at all. Unlike a KYC-style
  screen with an identification-document concept, this account-level
  screen has only two verdicts (:unresolved / :resolved) -- there is
  no middle 'incomplete' state here, so ANY non-clean signal from
  8291 (a definitive hit, 8291's own pending human review, or 8291's
  screen being held/rejected) lands on the SAME :unresolved verdict a
  local flag would produce -- never silently :resolved."
  [db {:keys [subject]} screen-fn]
  (let [a (store/account db subject)]
    (cond
      (nil? a)
      {:summary "対象口座記録が見つかりません" :rationale "no account record"
       :cites [] :effect :sanctions-screen/set :value {:account-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (true? (:sanctions-flag-unresolved? a))
      {:summary    (str (:holder-name a) ": 未解決の制裁リストフラグを検出")
       :rationale  "スクリーニングが未解決の制裁リストフラグを検出。人手確認とホールドが必須。"
       :cites      [:sanctions-check]
       :effect     :sanctions-screen/set
       :value      {:account-id subject :verdict :unresolved}
       :stake      nil
       :confidence 0.95}

      :else
      (let [ci (screen-fn (:holder-name a))]
        (cond
          (:hit? ci)
          {:summary    (str (:holder-name a) ": corporate-intelligence 照会で制裁/PEPフラグを検出")
           :rationale  "cloud-itonami-isic-8291 の名前スクリーニングが一致を検出。人手確認とホールドが必須。"
           :cites      [:corporate-intelligence]
           :effect     :sanctions-screen/set
           :value      {:account-id subject :verdict :unresolved}
           :stake      nil
           :confidence 0.9}

          (:pending-human-review? ci)
          {:summary    (str (:holder-name a) ": corporate-intelligence 照会が人手レビュー待ち")
           :rationale  "cloud-itonami-isic-8291 側の DisclosureGovernor が high-stakes escalate 中。確定するまで未解決として扱う(この口座の語彙に中間状態は無い)。"
           :cites      [:corporate-intelligence]
           :effect     :sanctions-screen/set
           :value      {:account-id subject :verdict :unresolved}
           :stake      nil
           :confidence 0.5}

          (:held? ci)
          {:summary    (str (:holder-name a) ": corporate-intelligence 照会が拒否された(契約/設定の問題)")
           :rationale  (str "cloud-itonami-isic-8291 の DisclosureGovernor が本テナントの照会を拒否: " (pr-str (:reason ci)))
           :cites      [:corporate-intelligence]
           :effect     :sanctions-screen/set
           :value      {:account-id subject :verdict :unresolved}
           :stake      nil
           :confidence 0.4}

          :else
          {:summary    (str (:holder-name a) ": 未解決の制裁リストフラグなし")
           :rationale  "制裁リストスクリーニング完了 + corporate-intelligence 照会クリア(または未収載)。"
           :cites      [:sanctions-check :corporate-intelligence]
           :effect     :sanctions-screen/set
           :value      {:account-id subject :verdict :resolved}
           :stake      nil
           :confidence 0.9})))))

(defn- propose-settlement
  "Draft the actual SETTLEMENT action -- posting a real balanced ledger
  settlement. ALWAYS `:stake :actuation/post-settlement` -- this is a
  REAL-WORLD banking act, never a draft the actor may auto-run. See
  README `Actuation`: no phase ever adds this op to a phase's `:auto`
  set (`banking.phase`); the governor also always escalates on
  `:actuation/post-settlement`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [a (store/account db subject)]
    {:summary    (str subject " 向け決済記帳提案"
                      (when a (str " (holder=" (:holder-name a) ")")))
     :rationale  (if a
                   (str "iban=" (:iban a))
                   "口座記録が見つかりません")
     :cites      (if a [subject] [])
     :effect     :account/mark-settled
     :value      {:account-id subject}
     :stake      :actuation/post-settlement
     :confidence (if (and a (not (registry/iban-checksum-invalid? a))) 0.9 0.3)}))

(defn- propose-interbank-message
  "Draft the actual INTERBANK-MESSAGE action -- dispatching a real
  SWIFT/ISO 20022 interbank message. ALWAYS `:stake :actuation/
  dispatch-interbank-message` -- this is a REAL-WORLD banking act,
  never a draft the actor may auto-run. See README `Actuation`: no
  phase ever adds this op to a phase's `:auto` set (`banking.phase`);
  the governor also always escalates on `:actuation/dispatch-
  interbank-message`. Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [a (store/account db subject)]
    {:summary    (str subject " 向け為替メッセージ発信提案"
                      (when a (str " (holder=" (:holder-name a) ")")))
     :rationale  (if a
                   "sanctions-screening-record referenced"
                   "口座記録が見つかりません")
     :cites      (if a [subject] [])
     :effect     :account/mark-dispatched
     :value      {:account-id subject}
     :stake      :actuation/dispatch-interbank-message
     :confidence (if a 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}
  `screen-fn` (default: `default-corporate-intel-screen`, a no-op) is
  only consulted by `:sanctions/screen`, once the local flag is
  otherwise clean."
  ([db request] (infer db request default-corporate-intel-screen))
  ([db {:keys [op] :as request} screen-fn]
   (case op
     :account/intake                          (normalize-intake db request)
     :compliance/verify                       (verify-compliance db request)
     :sanctions/screen                        (screen-sanctions db request screen-fn)
     :actuation/post-settlement                (propose-settlement db request)
     :actuation/dispatch-interbank-message      (propose-interbank-message db request)
     {:summary "未対応の操作" :rationale (str op) :cites []
      :effect :noop :stake nil :confidence 0.0})))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere.
  opts:
    :corporate-intel-screen -- holder name -> corporate-intel result (see
      `banking.corporate-intel/screen`). Default: no-op (never changes a
      screen-sanctions verdict), so `(mock-advisor)` with no args keeps
      every existing caller's exact prior behavior."
  ([] (mock-advisor {}))
  ([{:keys [corporate-intel-screen]
     :or   {corporate-intel-screen default-corporate-intel-screen}}]
   (reify Advisor (-advise [_ st req] (infer st req corporate-intel-screen)))))

(def ^:private system-prompt
  (str "あなたは地域金融仲介事業の決済記帳・為替メッセージ発信エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:account/upsert|:compliance/set|:sanctions-screen/set|"
       ":account/mark-settled|:account/mark-dispatched) "
       ":stake(:actuation/post-settlement か :actuation/dispatch-interbank-message か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :compliance/verify                       {:account (store/account st subject)}
    :sanctions/screen                        {:account (store/account st subject)}
    :actuation/post-settlement                {:account (store/account st subject)}
    :actuation/dispatch-interbank-message      {:account (store/account st subject)}
    {:account (store/account st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Monetary Intermediation
  Governor escalates/holds -- an LLM hiccup can never auto-post a
  settlement or auto-dispatch an interbank message."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :bankingadvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
