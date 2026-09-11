(ns cinemaops.governor
  "CinemaOpsGovernor -- the independent compliance layer that earns
  the CinemaOpsAdvisor the right to commit. The advisor has no notion
  of whether a screening is actually registered and verified, whether
  its own proposed `:effect` secretly claims a direct actuation
  instead of a mere proposal, or whether it has silently drifted into
  a permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  ONLY (showtime/attendance/print-quality logging, showtime/screen-
  allocation scheduling proposals, DCP/print delivery coordination,
  patron-safety-concern flagging). It NEVER performs or authorizes:
    - finalizing a patron-safety-authority decision (an evacuation
      override -- e.g. a decision to keep the theater open during an
      evacuation alarm)
    - finalizing an age-rating admission-check override (a decision to
      admit a patron despite the age-rating restriction)
    - directly actuating projection/booth equipment (starting/
      stopping the projector, applying a DCP decryption key, operating
      the digital cinema server)
    - directly controlling fire/life-safety systems (fire alarms,
      sprinkler/suppression systems, fire doors)

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Screening unverified       -- the target screening record must
                                      exist AND be independently
                                      confirmed `:registered?`/
                                      `:verified?` in the store before
                                      ANY proposal for it may commit or
                                      even escalate. Never trusts a
                                      proposal's own claim about the
                                      screening -- re-derived from the
                                      screening's own store record, the
                                      same 'ground truth, not
                                      self-report' discipline every
                                      sibling actor's governor uses.
    2. Effect not :propose        -- every proposal's `:effect` MUST
                                      be `:propose`. Any other effect
                                      value is, by construction, a
                                      claim to directly actuate/commit
                                      outside governance -- HARD block,
                                      not merely low-confidence.
    3. Scope exclusion            -- ANY proposal (regardless of op)
                                      whose op, rationale, summary,
                                      citations or draft value directly
                                      finalizes a patron-safety-
                                      authority decision (evacuation
                                      override, age-rating admission
                                      override) or directly actuates
                                      projection/booth or fire/life-
                                      safety equipment is a HARD,
                                      PERMANENT block -- this actor's
                                      charter excludes that territory
                                      structurally, not as a rollout
                                      milestone. Evaluated
                                      UNCONDITIONALLY on every
                                      proposal. An op outside the
                                      closed four-op allowlist is the
                                      SAME failure mode (an advisor
                                      proposing something it was never
                                      authorized to propose) and is
                                      folded into this same check.

  CRITICAL, and un-overridable by rollout phase: `scope-excluded-terms`
  below is phrased as the finalization/execution ACTION (\"override the
  evacuation order\", \"directly control the projector\"), never as a
  bare noun (\"evacuation\", \"age rating\", \"projector\"). A bare-noun
  phrasing would self-trip on this actor's own core valid use case --
  `:flag-patron-safety-concern`'s entire purpose is to report raw
  observations that legitimately mention fire alarms, evacuation
  routes, and age-rating admission concerns (see
  `legitimate-patron-safety-concern-is-not-scope-excluded` in
  `governor_test.clj` and `default-mock-advisor-proposals-never-self-trip-
  scope-exclusion` in `governor_contract_test.clj`, which assert the
  default mock advisor's own proposals -- including the safety-concern
  one -- never self-trip this gate).

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-patron-safety-concern` -- ALWAYS escalates to a human,
  regardless of confidence, regardless of how clean the proposal
  otherwise is. `cinemaops.phase` independently agrees:
  `:flag-patron-safety-concern` is never a member of any phase's
  `:auto` set either -- two layers, not one."
  (:require [kotoba.lang.text :as str]
            [cinemaops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-screening-record :schedule-screening-operation
    :coordinate-print-delivery :flag-patron-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-patron-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- a patron-safety-authority
  finalization (evacuation override, age-rating admission override),
  or a direct actuation of projection/booth or fire/life-safety
  equipment. Deliberately phrased as the finalization/execution
  ACTION, never a bare noun -- see the governor docstring's CRITICAL
  note above for why a bare-noun phrasing would self-trip on this
  actor's own legitimate `:flag-patron-safety-concern` use case.
  Scanned across the proposal's op/summary/rationale/cites/value,
  never trusting the advisor's own framing of its intent."
  ["override the evacuation order" "cancel the evacuation order"
   "keep the theater open during the evacuation" "keep the venue open during the fire alarm"
   "finalize the evacuation override" "decision to not evacuate the auditorium"
   "避難命令を無効にする判断" "火災警報中も上映を継続する決定" "避難を中止する決定"
   "override the age-rating admission check" "override the age rating check"
   "bypass the age verification requirement" "admit despite the age rating restriction"
   "finalize the age-rating override" "年齢確認を無効にする判断" "年齢制限を無視して入場を許可する決定"
   "directly control the projector" "actuate the projection equipment"
   "start the projector remotely" "apply the kdm decryption key"
   "override the projection booth controls" "directly operate the digital cinema server"
   "映写機を直接操作する" "上映設備を直接制御する" "復号鍵を適用する操作"
   "silence the fire alarm system" "override the sprinkler system"
   "disable the fire suppression system" "unlock the fire exit override"
   "directly control the fire door" "火災報知器を無効にする操作" "スプリンクラーを直接制御する"])

;; ----------------------------- checks -----------------------------

(defn- screening-unverified-violations
  "The target screening must exist AND be independently `:registered?`/
  `:verified?` in the store -- never trust the proposal's own
  `:screening-id` claim without a store lookup."
  [{:keys [screening-id]} st]
  (let [r (store/screening st screening-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :screening-unverified
        :detail (str screening-id " は未登録または未検証の上映 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content directly finalizes a patron-safety-authority
  decision or directly actuates projection/booth or fire/life-safety
  equipment, regardless of confidence or how clean every other check
  is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "観客安全当局の判断(避難命令の無効化・年齢確認の無効化)や上映設備・防火設備の直接操作に触れる提案は永久に禁止"}])))

(defn check
  "Censors a CinemaOpsAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [screening-id (or (:screening-id proposal) (:screening-id request))
        hard (into []
                   (concat (screening-unverified-violations {:screening-id screening-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :screening-id (:screening-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
