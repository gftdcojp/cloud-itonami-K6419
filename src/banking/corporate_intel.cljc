(ns banking.corporate-intel
  "Optional integration with `cloud-itonami-isic-8291` (Dossier-LLM ⊣
  DisclosureGovernor corporate/compliance intelligence actor) — this
  blueprint's own `:optional-technologies :corporate-intelligence`
  declaration made real, not just declared.

  Cross-references an account holder's name against 8291's sourced
  PEP/sanctions data via the SAME governed `:disclosure/screen-name`
  op any other licensed consumer would use — there is no bypass of
  8291's own DisclosureGovernor from this side either. In particular,
  when 8291 escalates a potential hit for its OWN human reviewer to
  confirm, this namespace does NOT peek at Dossier-LLM's un-vetted
  draft proposal to get an early answer — it reports
  `:pending-human-review?` and lets
  `banking.bankingadvisor/screen-sanctions` treat that the same as any
  other unresolved signal (this repo's vocabulary has no separate
  'inconclusive' state, so unresolved wins over clear — see
  `banking.bankingadvisor`'s docstring on `:unresolved`).

  Swappable like every other dependency in this fleet (Store/Advisor/
  Phase): `screen` defaults to a demo 8291 MemStore + fresh actor per
  call, but takes an already-built `:actor` for production (one built
  once, against a real Store, under a real contract this blueprint's
  operator negotiated with the 8291 operator)."
  (:require [langgraph.graph :as g]
            [dossier.store :as dstore]
            [dossier.operation :as dop]))

(def default-tenant
  "This blueprint's own tenant id under an 8291 contract. A real
  deployment registers this (or an operator-chosen tenant id) with
  whichever 8291 instance/operator it has a compliance-tier contract
  with."
  "cloud-itonami-isic-6419")

(defn demo-store
  "An 8291 MemStore seeded with 8291's own demo data, PLUS a contract
  for THIS blueprint's tenant at `:tier/compliance` (name-screening
  requires at least that tier — see cloud-itonami-isic-8291's
  `dossier.policy/tier-columns`). Replaces 8291's own demo tenant-acme/
  tenant-basic contracts entirely: this is 6419's OWN isolated offline
  view, not a shared runtime instance with 8291's demo fixtures."
  []
  (-> (dstore/seed-db)
      (dstore/with-contracts
       {default-tenant {:tenant default-tenant :tier :tier/compliance
                         :active? true :purpose :kyc-screening}})))

(defn build
  "Compiles an 8291 OperationActor bound to `store` (default: `demo-store`)."
  ([] (build (demo-store)))
  ([store] (dop/build store)))

(defn screen
  "Runs a `:disclosure/screen-name` op against 8291 for `name`. Returns
  one of:
    {:found? bool :hit? bool :capacity kw|nil :org str|nil}  -- a
      governor-approved answer (disposition :commit): either no
      matching official on file at all, or a matching official
      cleanly not flagged.
    {:pending-human-review? true :reason kw}                -- 8291
      itself escalated a potential hit to ITS OWN human reviewer;
      treat as unresolved, not as a hit or a clear.
    {:held? true :reason [kw ..]}                            -- the
      screen itself was rejected by 8291's DisclosureGovernor (e.g.
      this tenant's contract is missing/inactive/wrong tier on the
      Store actually in use) — a configuration problem on the calling
      side, not a finding about `name`. Never silently treated as
      clear.

  opts:
    :actor     -- a pre-built 8291 OperationActor (default: fresh `build`)
    :tenant    -- tenant id to screen under (default: `default-tenant`)
    :thread-id -- langgraph-clj thread id (default: derived from `name`)"
  ([name] (screen name {}))
  ([name {:keys [actor tenant thread-id]
          :or   {actor (build) tenant default-tenant}}]
   (let [thread-id (or thread-id (str "screen-" tenant "-" name))
         res (g/run* actor
                     {:request {:op :disclosure/screen-name :subject tenant :name name}
                      :context {:actor-id default-tenant :actor-role :client :tenant tenant}}
                     {:thread-id thread-id})]
     (case (get-in res [:state :disposition])
       :commit    (get-in res [:state :record :value])
       :escalate  {:pending-human-review? true
                   :reason (-> res :state :audit last :reason)}
       :hold      {:held? true
                   :reason (-> res :state :audit last :basis)}
       {:held? true :reason [:corporate-intel-actor-error]}))))
