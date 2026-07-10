(ns banking.store
  "SSoT for the banking actor, behind a `Store` protocol so the backend
  is a swap, not a rewrite -- the same seam every prior `cloud-
  itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/banking/store_contract_test.clj), which is the whole point:
  the actor, the Monetary Intermediation Governor and the audit
  ledger never know which SSoT they run on.

  Like every prior dual-actuation sibling, this actor has TWO
  actuation events (posting a settlement, dispatching an interbank
  message) acting on the SAME entity (an `account`), each with its OWN
  history collection, sequence counter and dedicated double-
  actuation-guard boolean (`:settlement-posted?`/`:interbank-message-
  dispatched?`, never a `:status` value) -- the same discipline every
  prior sibling governor's guards establish, informed by `cloud-
  itonami-isic-6492`'s status-lifecycle bug (ADR-2607071320).

  The ledger stays append-only on every backend: 'which account was
  screened for an unresolved sanctions flag, which settlement was
  posted, which interbank message was dispatched, on what
  jurisdictional basis, approved by whom' is always a query over an
  immutable log -- the audit trail a depositor trusting a banking
  operator needs, and the evidence an operator needs if a settlement
  or dispatch decision is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [banking.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (account [s id])
  (all-accounts [s])
  (sanctions-screen-of [s account-id] "committed sanctions-screening verdict for an account, or nil")
  (compliance-of [s account-id] "committed AML/KYC evidence assessment, or nil")
  (ledger [s])
  (settlement-history [s] "the append-only settlement history (banking.registry drafts)")
  (interbank-message-history [s] "the append-only interbank-message history (banking.registry drafts)")
  (next-settlement-sequence [s jurisdiction] "next settlement-number sequence for a jurisdiction")
  (next-message-sequence [s jurisdiction] "next message-number sequence for a jurisdiction")
  (account-already-settled? [s account-id] "has this account's settlement already been posted?")
  (account-already-dispatched? [s account-id] "has this account's interbank message already been dispatched?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-accounts [s accounts] "replace/seed the account directory (map id->account)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained account set covering both actuation
  lifecycles (posting a settlement, dispatching an interbank message)
  so the actor + tests run offline. `:iban` values include one real,
  valid published IBAN test vector (Deutsche Bundesbank's own
  DE89370400440532013000) and one deliberately corrupted variant."
  []
  {:accounts
   {"account-1" {:id "account-1" :holder-name "Sato Ichiro"
                :iban "DE89370400440532013000"
                :sanctions-flag-unresolved? false
                :settlement-posted? false :interbank-message-dispatched? false
                :jurisdiction "JPN" :status :intake}
    "account-2" {:id "account-2" :holder-name "Atlantis Doe"
                :iban "DE89370400440532013000"
                :sanctions-flag-unresolved? false
                :settlement-posted? false :interbank-message-dispatched? false
                :jurisdiction "ATL" :status :intake}
    "account-3" {:id "account-3" :holder-name "鈴木健太"
                :iban "DE89370400440532013099"
                :sanctions-flag-unresolved? false
                :settlement-posted? false :interbank-message-dispatched? false
                :jurisdiction "JPN" :status :intake}
    "account-4" {:id "account-4" :holder-name "田中麻衣"
                :iban "DE89370400440532013000"
                :sanctions-flag-unresolved? true
                :settlement-posted? false :interbank-message-dispatched? false
                :jurisdiction "JPN" :status :intake}
    ;; account-5: clean on every LOCAL field (no `:sanctions-flag-
    ;; unresolved?`) but shares its `:holder-name` with
    ;; cloud-itonami-isic-8291's own demo sanctions-flagged official
    ;; ("Jane Smith (demo)", `of-2`/`co-200` in `dossier.store/demo-
    ;; data`). Exists purely to prove `banking.corporate-intel`'s
    ;; cross-reference into 8291 catches a holder this repo's local-
    ;; only sanctions check alone would silently clear -- see
    ;; `test/banking/corporate_intel_test.clj`.
    "account-5" {:id "account-5" :holder-name "Jane Smith (demo)"
                :iban "DE89370400440532013000"
                :sanctions-flag-unresolved? false
                :settlement-posted? false :interbank-message-dispatched? false
                :jurisdiction "GBR" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- post-settlement!
  "Backend-agnostic `:account/mark-settled` -- looks up the account via
  the protocol and drafts the settlement record, and returns {:result
  .. :account-patch ..} for the caller to persist."
  [s account-id]
  (let [a (account s account-id)
        seq-n (next-settlement-sequence s (:jurisdiction a))
        result (registry/register-settlement account-id (:jurisdiction a) seq-n)]
    {:result result
     :account-patch {:settlement-posted? true
                    :settlement-number (get result "settlement_number")}}))

(defn- dispatch-interbank-message!
  "Backend-agnostic `:account/mark-dispatched` -- looks up the account
  via the protocol and drafts the interbank-message record, and
  returns {:result .. :account-patch ..} for the caller to persist."
  [s account-id]
  (let [a (account s account-id)
        seq-n (next-message-sequence s (:jurisdiction a))
        result (registry/register-interbank-message account-id (:jurisdiction a) seq-n)]
    {:result result
     :account-patch {:interbank-message-dispatched? true
                    :message-number (get result "message_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (account [_ id] (get-in @a [:accounts id]))
  (all-accounts [_] (sort-by :id (vals (:accounts @a))))
  (sanctions-screen-of [_ id] (get-in @a [:sanctions-screens id]))
  (compliance-of [_ account-id] (get-in @a [:compliance-assessments account-id]))
  (ledger [_] (:ledger @a))
  (settlement-history [_] (:settlements @a))
  (interbank-message-history [_] (:interbank-messages @a))
  (next-settlement-sequence [_ jurisdiction] (get-in @a [:settlement-sequences jurisdiction] 0))
  (next-message-sequence [_ jurisdiction] (get-in @a [:message-sequences jurisdiction] 0))
  (account-already-settled? [_ account-id] (boolean (get-in @a [:accounts account-id :settlement-posted?])))
  (account-already-dispatched? [_ account-id] (boolean (get-in @a [:accounts account-id :interbank-message-dispatched?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :account/upsert
      (swap! a update-in [:accounts (:id value)] merge value)

      :compliance/set
      (swap! a assoc-in [:compliance-assessments (first path)] payload)

      :sanctions-screen/set
      (swap! a assoc-in [:sanctions-screens (first path)] payload)

      :account/mark-settled
      (let [account-id (first path)
            {:keys [result account-patch]} (post-settlement! s account-id)
            jurisdiction (:jurisdiction (account s account-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:settlement-sequences jurisdiction] (fnil inc 0))
                       (update-in [:accounts account-id] merge account-patch)
                       (update :settlements registry/append result))))
        result)

      :account/mark-dispatched
      (let [account-id (first path)
            {:keys [result account-patch]} (dispatch-interbank-message! s account-id)
            jurisdiction (:jurisdiction (account s account-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:message-sequences jurisdiction] (fnil inc 0))
                       (update-in [:accounts account-id] merge account-patch)
                       (update :interbank-messages registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-accounts [s accounts] (when (seq accounts) (swap! a assoc :accounts accounts)) s))

(defn seed-db
  "A MemStore seeded with the demo account set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :compliance-assessments {} :sanctions-screens {} :ledger [] :settlement-sequences {}
                           :settlements [] :message-sequences {} :interbank-messages []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Compound values (compliance/sanctions-screen payloads, ledger facts,
  settlement/interbank-message records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:account/id                          {:db/unique :db.unique/identity}
   :compliance/account-id               {:db/unique :db.unique/identity}
   :sanctions-screen/account-id         {:db/unique :db.unique/identity}
   :ledger/seq                         {:db/unique :db.unique/identity}
   :settlement/seq                     {:db/unique :db.unique/identity}
   :interbank-message/seq              {:db/unique :db.unique/identity}
   :settlement-sequence/jurisdiction   {:db/unique :db.unique/identity}
   :message-sequence/jurisdiction      {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- account->tx [{:keys [id holder-name iban
                          sanctions-flag-unresolved?
                          settlement-posted? interbank-message-dispatched?
                          jurisdiction status settlement-number message-number]}]
  (cond-> {:account/id id}
    holder-name                                  (assoc :account/holder-name holder-name)
    iban                                          (assoc :account/iban iban)
    (some? sanctions-flag-unresolved?)            (assoc :account/sanctions-flag-unresolved? sanctions-flag-unresolved?)
    (some? settlement-posted?)                    (assoc :account/settlement-posted? settlement-posted?)
    (some? interbank-message-dispatched?)         (assoc :account/interbank-message-dispatched? interbank-message-dispatched?)
    jurisdiction                                   (assoc :account/jurisdiction jurisdiction)
    status                                         (assoc :account/status status)
    settlement-number                              (assoc :account/settlement-number settlement-number)
    message-number                                 (assoc :account/message-number message-number)))

(def ^:private account-pull
  [:account/id :account/holder-name :account/iban
   :account/sanctions-flag-unresolved? :account/settlement-posted? :account/interbank-message-dispatched?
   :account/jurisdiction :account/status :account/settlement-number :account/message-number])

(defn- pull->account [m]
  (when (:account/id m)
    {:id (:account/id m) :holder-name (:account/holder-name m)
     :iban (:account/iban m)
     :sanctions-flag-unresolved? (boolean (:account/sanctions-flag-unresolved? m))
     :settlement-posted? (boolean (:account/settlement-posted? m))
     :interbank-message-dispatched? (boolean (:account/interbank-message-dispatched? m))
     :jurisdiction (:account/jurisdiction m) :status (:account/status m)
     :settlement-number (:account/settlement-number m) :message-number (:account/message-number m)}))

(defrecord DatomicStore [conn]
  Store
  (account [_ id]
    (pull->account (d/pull (d/db conn) account-pull [:account/id id])))
  (all-accounts [_]
    (->> (d/q '[:find [?id ...] :where [?e :account/id ?id]] (d/db conn))
         (map #(pull->account (d/pull (d/db conn) account-pull [:account/id %])))
         (sort-by :id)))
  (sanctions-screen-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?k :sanctions-screen/account-id ?aid] [?k :sanctions-screen/payload ?p]]
              (d/db conn) id)))
  (compliance-of [_ account-id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?a :compliance/account-id ?aid] [?a :compliance/payload ?p]]
              (d/db conn) account-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (settlement-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :settlement/seq ?s] [?e :settlement/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (interbank-message-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :interbank-message/seq ?s] [?e :interbank-message/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-settlement-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :settlement-sequence/jurisdiction ?j] [?e :settlement-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-message-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :message-sequence/jurisdiction ?j] [?e :message-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (account-already-settled? [s account-id]
    (boolean (:settlement-posted? (account s account-id))))
  (account-already-dispatched? [s account-id]
    (boolean (:interbank-message-dispatched? (account s account-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :account/upsert
      (d/transact! conn [(account->tx value)])

      :compliance/set
      (d/transact! conn [{:compliance/account-id (first path) :compliance/payload (enc payload)}])

      :sanctions-screen/set
      (d/transact! conn [{:sanctions-screen/account-id (first path) :sanctions-screen/payload (enc payload)}])

      :account/mark-settled
      (let [account-id (first path)
            {:keys [result account-patch]} (post-settlement! s account-id)
            jurisdiction (:jurisdiction (account s account-id))
            next-n (inc (next-settlement-sequence s jurisdiction))]
        (d/transact! conn
                     [(account->tx (assoc account-patch :id account-id))
                      {:settlement-sequence/jurisdiction jurisdiction :settlement-sequence/next next-n}
                      {:settlement/seq (count (settlement-history s)) :settlement/record (enc (get result "record"))}])
        result)

      :account/mark-dispatched
      (let [account-id (first path)
            {:keys [result account-patch]} (dispatch-interbank-message! s account-id)
            jurisdiction (:jurisdiction (account s account-id))
            next-n (inc (next-message-sequence s jurisdiction))]
        (d/transact! conn
                     [(account->tx (assoc account-patch :id account-id))
                      {:message-sequence/jurisdiction jurisdiction :message-sequence/next next-n}
                      {:interbank-message/seq (count (interbank-message-history s)) :interbank-message/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-accounts [s accounts]
    (when (seq accounts) (d/transact! conn (mapv account->tx (vals accounts)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:accounts ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [accounts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-accounts s accounts))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo account set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
