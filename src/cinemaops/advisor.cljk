(ns cinemaops.advisor
  "CinemaOpsAdvisor -- the *contained intelligence node* for the
  ISIC-5914 motion-picture-projection (cinema/theater exhibition)
  operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: showtime/attendance/print-quality logging, showtime/
  screen-allocation scheduling proposals, digital-cinema-package
  (DCP)/print delivery coordination, and patron-safety-concern
  flagging. CRITICAL: it is a smart-but-untrusted advisor. It returns
  a *proposal* (with a rationale + the fields it cited), never a
  committed record and NEVER a direct actuation -- every proposal's
  `:effect` is always `:propose`. Every output is censored downstream
  by `cinemaops.governor` before anything touches the SSoT.

  This advisor NEVER drafts a proposal that directly finalizes a
  patron-safety-authority decision (an evacuation override, an
  age-rating admission override), and NEVER directly actuates
  projection/booth equipment or fire/life-safety systems -- those are
  permanently out of scope for this actor, not merely un-implemented.
  `cinemaops.governor`'s `scope-exclusion-violations` independently
  re-scans every proposal for exactly this failure mode (a compromised
  or confused advisor drifting into scope it must never touch) and
  HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op          kw             ; echoes the request op
     :screening-id str
     :summary     str            ; human-facing draft / finding
     :rationale   str            ; why -- SCANNED by the scope-exclusion gate
     :cites       [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect      :propose       ; ALWAYS :propose -- never a direct actuation
     :value       map            ; the draft payload a human/system would review
     :confidence  0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-screening-record
  "Draft a showtime/attendance/print-quality log entry. Pure logging
  of observed screening operations -- never equipment actuation."
  [_db {:keys [screening-id patch]}]
  {:op          :log-screening-record
   :screening-id screening-id
   :summary     (str screening-id " の上映記録を記録: " (pr-str (keys patch)))
   :rationale   "上映時間・入場者数・プリント品質の観察記録のみ。設備の直接操作は行わない。"
   :cites       [screening-id]
   :effect      :propose
   :value       (merge {:screening-id screening-id} patch)
   :confidence  0.93})

(defn- propose-screening-schedule
  "Draft a showtime/screen-allocation scheduling PROPOSAL only (never a
  binding schedule change). Final confirmation is always done by the
  theater operations manager."
  [_db {:keys [screening-id patch]}]
  {:op          :schedule-screening-operation
   :screening-id screening-id
   :summary     (str screening-id " の上映スケジュール調整提案: " (pr-str (keys patch)))
   :rationale   "上映時間・スクリーン割当の調整提案のみ。最終確定は劇場運営責任者が行う。"
   :cites       [screening-id]
   :effect      :propose
   :value       (merge {:screening-id screening-id} patch)
   :confidence  0.88})

(defn- propose-print-delivery
  "Draft a DCP/print delivery coordination request (scheduling/
  logistics only -- never a decryption-key application or equipment
  operation)."
  [_db {:keys [screening-id patch]}]
  {:op          :coordinate-print-delivery
   :screening-id screening-id
   :summary     (str screening-id " のDCP/プリント配送調整: " (pr-str (keys patch)))
   :rationale   "DCP/プリントの配送スケジュール調整のみ。設備の操作は含まない。"
   :cites       [screening-id]
   :effect      :propose
   :value       (merge {:screening-id screening-id} patch)
   :confidence  0.90})

(defn- propose-patron-safety-concern
  "Surface a patron-safety concern (fire/egress, disturbance,
  age-rating-admission concern) for HUMAN triage. This op ALWAYS
  escalates in `cinemaops.governor` -- never auto-committed at any
  phase -- regardless of how confident the advisor is that the concern
  is real."
  [_db {:keys [screening-id patch]}]
  {:op          :flag-patron-safety-concern
   :screening-id screening-id
   :summary     (str screening-id " の観客安全懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale   "劇場内で観察された安全に関する事実（火災警報の作動、避難経路の混雑、入場時の年齢確認の懸念等）の報告。常に人間の確認・対応が必要。"
   :cites       [screening-id]
   :effect      :propose
   :value       (merge {:screening-id screening-id} patch)
   :confidence  (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-screening-record (propose-screening-record _db request)
                   :schedule-screening-operation (propose-screening-schedule _db request)
                   :coordinate-print-delivery (propose-print-delivery _db request)
                   :flag-patron-safety-concern (propose-patron-safety-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually override the evacuation order and directly control the projector")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :screening-id (:screening-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
