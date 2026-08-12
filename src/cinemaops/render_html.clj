(ns cinemaops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-5914`: this
  repo had a product face (`docs/index.html`) but no operator console
  and no generator for one.

  Everything on the generated page is produced by actually RUNNING this
  repo's own actor stack -- `cinemaops.operation` (a langgraph-clj
  StateGraph) -> `cinemaops.governor` -> `cinemaops.phase` ->
  `cinemaops.store`. No hand-written rows: every screening id, film,
  screen, showtime, disposition, violation rule and violation detail
  string is read back out of the store/ledger after the scenario in
  `run-demo!` has been driven through the real graph. The screening
  directory is exactly `cinemaops.store/demo-data`; the attendance
  figures (142, 98) are the ones this repo's own `cinemaops.sim` demo
  driver uses.

  Deterministic on purpose: no timestamps, no clock reads, no
  randomness, and every set/map is sorted explicitly before it reaches
  the page, so two consecutive runs are byte-identical.

  `-main` REFUSES to write the file when the scenario produced no HARD
  governor hold -- a console that shows no real hold would be
  advertising a compliance layer nobody exercised.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [cinemaops.advisor :as advisor]
            [cinemaops.governor :as governor]
            [cinemaops.operation :as op]
            [cinemaops.phase :as phase]
            [cinemaops.store :as store]
            [langgraph.graph :as g]))

;; ----------------------------- scenario -----------------------------

(def ^:private coordinator-id "coord-1")
(def ^:private approver-id "theater-ops-coordinator-1")

(defn- ctx
  "The injected actor context for a run at rollout `phase`."
  [phase]
  {:actor-id coordinator-id :actor-role :theater-ops-coordinator :phase phase})

(defn- exec! [actor tid request phase]
  (g/run* actor {:request request :context (ctx phase)} {:thread-id tid}))

(defn- resume! [actor tid approval]
  (g/run* actor {:approval approval} {:thread-id tid :resume? true}))

(def ^:private drift-phrases
  "Scope-exclusion probes, one per permanently-excluded decision area.
  Each string is taken verbatim from `cinemaops.governor/scope-excluded-terms`
  -- `verified-drift-phrases` below re-checks that membership at render
  time, so the page can never claim a phrase the governor does not
  actually carry."
  [[:evacuation-authority "避難命令の無効化(観客安全当局の判断)" "override the evacuation order"]
   [:age-rating-authority "年齢確認の無効化(入場可否の最終判断)" "override the age-rating admission check"]
   [:projection-actuation "映写設備の直接操作(復号鍵の適用)" "apply the kdm decryption key"]
   [:fire-safety-actuation "防火設備の直接操作(火災報知器の停止)" "silence the fire alarm system"]])

(defn- drifting-advisor
  "An advisor that drafts a normal proposal and then drifts one
  permanently out-of-scope sentence into its own rationale -- the
  compromised/confused-advisor failure mode `scope-exclusion-violations`
  exists to catch."
  [phrase]
  (reify advisor/Advisor
    (-advise [_ _store request]
      (update (advisor/infer nil request) :rationale str " -- actually " phrase))))

(defn- direct-actuation-advisor
  "An advisor that claims a direct commit instead of a proposal."
  []
  (reify advisor/Advisor
    (-advise [_ _store request]
      (assoc (advisor/infer nil request) :effect :commit))))

(defn- rogue-op-advisor
  "An advisor that drafts a well-formed, high-confidence,
  `:effect :propose` proposal for an op it was never authorized to
  propose -- outside `cinemaops.governor/allowed-ops`."
  [rogue-op]
  (reify advisor/Advisor
    (-advise [_ _store {:keys [screening-id]}]
      {:op rogue-op
       :screening-id screening-id
       :summary (str screening-id " の入場料払い戻しを起票")
       :rationale "上映運営の付随処理として起票した(この actor の許可操作ではない)。"
       :cites [screening-id]
       :effect :propose
       :value {:screening-id screening-id}
       :confidence 0.97})))

(defn- low-confidence-advisor
  "An advisor whose own confidence is below
  `cinemaops.governor/confidence-floor`."
  [conf]
  (reify advisor/Advisor
    (-advise [_ _store request]
      (assoc (advisor/infer nil request) :confidence conf))))

(defn- cases
  "The scenario, as data. Every `:screening-id` is a key of
  `cinemaops.store/demo-data`, except `screening-99`, which is
  deliberately absent from it (that absence is the point of R10)."
  [db]
  (let [s1 (store/screening db "screening-1")
        s2 (store/screening db "screening-2")
        s3 (store/screening db "screening-3")
        [[_ evac-label evac] [_ age-label age]
         [_ proj-label proj] [_ fire-label fire]] drift-phrases]
    [{:id "R01" :phase 1
      :note "phase 1 は書き込みに必ず人間承認が要る"
      :request {:op :log-screening-record :screening-id (:screening-id s1)
                :patch {:attendance 142 :print-quality "clean" :screen (:screen s1)}}
      :human {:status :approved :by approver-id}}

     {:id "R02" :phase 3
      :note "phase 3 の自動確定(governor clean + 高信頼)"
      :request {:op :log-screening-record :screening-id (:screening-id s1)
                :patch {:attendance 98 :print-quality "clean" :screen (:screen s1)}}}

     {:id "R03" :phase 3
      :note "スクリーン再割当の提案(確定は劇場運営責任者)"
      :request {:op :schedule-screening-operation :screening-id (:screening-id s2)
                :patch {:current-screen (:screen s2) :proposed-screen (:screen s1)
                        :showtime (:showtime s2)}}}

     {:id "R04" :phase 3
      :note "DCP/プリント配送の調整(設備操作は含まない)"
      :request {:op :coordinate-print-delivery :screening-id (:screening-id s2)
                :patch {:film (:film s2) :screen (:screen s2) :deliver-before (:showtime s2)}}}

     {:id "R05" :phase 3
      :note "観客安全懸念は phase 3 でも必ず人間へ。火災警報に言及しても scope-exclusion に自爆しない"
      :request {:op :flag-patron-safety-concern :screening-id (:screening-id s1)
                :patch {:concern "fire alarm briefly sounded near screen 3 exit corridor"
                        :screen (:screen s1) :confidence 0.92}}
      :human {:status :approved :by approver-id}}

     {:id "R06" :phase 3
      :note "人間が却下 -- SSoT へは何も書かれない"
      :request {:op :flag-patron-safety-concern :screening-id (:screening-id s2)
                :patch {:concern "egress route near screen 1 partially blocked by queue barrier"
                        :screen (:screen s2) :confidence 0.81}}
      :human {:status :rejected :by approver-id}}

     {:id "R07" :phase 3
      :advisor-impl (low-confidence-advisor 0.42)
      :advisor-label "low-confidence (0.42)"
      :note "信頼度が floor 未満 -- 自動確定させず人間へ"
      :request {:op :log-screening-record :screening-id (:screening-id s1)
                :patch {:attendance 142 :print-quality "scratched reel change" :screen (:screen s1)}}
      :human {:status :approved :by approver-id}}

     {:id "R08" :phase 0
      :note "phase 0 は read-only -- 書き込み op は一切通らない"
      :request {:op :log-screening-record :screening-id (:screening-id s2)
                :patch {:attendance 0 :screen (:screen s2)}}}

     {:id "R09" :phase 1
      :note "phase 1 では配送調整はまだ有効化されていない"
      :request {:op :coordinate-print-delivery :screening-id (:screening-id s2)
                :patch {:film (:film s2) :deliver-before (:showtime s2)}}}

     {:id "R10" :phase 3
      :note "seed に存在しない上映 -- 未登録"
      :request {:op :log-screening-record :screening-id "screening-99"
                :patch {:attendance 0}}}

     {:id "R11" :phase 3
      :note "登録済みだが :verified? false(改装後の新スクリーン)"
      :request {:op :schedule-screening-operation :screening-id (:screening-id s3)
                :patch {:screen (:screen s3) :showtime (:showtime s3)}}}

     {:id "R12" :phase 3 :advisor-impl (direct-actuation-advisor)
      :advisor-label "direct-actuation (:effect :commit)"
      :note "提案ではなく直接確定を主張した"
      :request {:op :schedule-screening-operation :screening-id (:screening-id s1)
                :patch {:screen (:screen s1) :showtime (:showtime s1)}}}

     {:id "R13" :phase 3 :advisor-impl (rogue-op-advisor :issue-patron-refund)
      :advisor-label "rogue-op (:issue-patron-refund, conf 0.97)"
      :note "許可 op の外 -- 信頼度 0.97 でも救われない"
      :request {:op :log-screening-record :screening-id (:screening-id s1) :patch {}}}

     {:id "R14" :phase 3 :advisor-impl (drifting-advisor evac)
      :advisor-label (str "scope-drift / " evac-label)
      :note evac-label
      :request {:op :log-screening-record :screening-id (:screening-id s1)
                :patch {:attendance 98 :screen (:screen s1)}}}

     {:id "R15" :phase 3 :advisor-impl (drifting-advisor age)
      :advisor-label (str "scope-drift / " age-label)
      :note age-label
      :request {:op :log-screening-record :screening-id (:screening-id s2)
                :patch {:attendance 0 :screen (:screen s2)}}}

     {:id "R16" :phase 3 :advisor-impl (drifting-advisor proj)
      :advisor-label (str "scope-drift / " proj-label)
      :note proj-label
      :request {:op :coordinate-print-delivery :screening-id (:screening-id s1)
                :patch {:film (:film s1) :deliver-before (:showtime s1)}}}

     {:id "R17" :phase 3 :advisor-impl (drifting-advisor fire)
      :advisor-label (str "scope-drift / " fire-label)
      :note (str fire-label " -- 常に人間へ回る op でも、人間に届く前に HARD hold")
      :request {:op :flag-patron-safety-concern :screening-id (:screening-id s2)
                :patch {:concern "smoke detector fault indicator on screen 1 panel"
                        :screen (:screen s2) :confidence 0.9}}}

     {:id "R18" :phase 2
      :note "interrupt-before で停止したまま -- 人間の判断待ち。台帳には何も書かれない"
      :request {:op :coordinate-print-delivery :screening-id (:screening-id s1)
                :patch {:film (:film s1) :screen (:screen s1) :deliver-before (:showtime s1)}}}]))

(defn- run-case!
  "Drives one case through a freshly built actor bound to `db`, resuming
  with the human decision when the graph interrupts and the case
  carries one. Returns the case enriched with the real run results."
  [db {:keys [id phase request human advisor-impl advisor-label] :as c}]
  (let [actor (if advisor-impl (op/build db {:advisor advisor-impl}) (op/build db))
        tid   (str "thread-" (str/lower-case id))
        r1    (exec! actor tid request phase)
        paused? (= :interrupted (:status r1))
        r2    (when (and paused? human) (resume! actor tid human))]
    (assoc c
           :thread-id tid
           :advisor-label (or advisor-label "mock (default)")
           :paused? paused?
           :awaiting? (and paused? (nil? human))
           :interrupt-state (when paused? (:state r1))
           :final-state (:state (or r2 r1))
           :final-status (:status (or r2 r1)))))

(defn run-demo!
  "Seeds a fresh `cinemaops.store` MemStore and drives 18 coordination
  requests through the REAL OperationActor graph.

  Coverage, by construction:
    - full clean lifecycles that auto-commit at phase 3 (R02-R04)
    - human-approved escalations: phase-gated (R01), always-escalate
      patron-safety (R05), low-confidence (R07)
    - a human REJECTION that writes a hold instead of the SSoT (R06)
    - rollout-phase holds that never reach a human (R08, R09)
    - all FOUR HARD governor rules: `:screening-unverified` (R10, R11),
      `:effect-not-propose` (R12), `:op-not-allowed` (R13) and
      `:scope-excluded` (R14-R17, one per permanently-excluded decision
      area)
    - an escalation still paused at `interrupt-before` (R18)

  Returns `{:db store :runs [..]}` -- `render` reads only these."
  []
  (let [db (store/seed-db)]
    {:db db
     :runs (mapv (partial run-case! db) (cases db))}))

;; ----------------------------- derivations -----------------------------

(defn hard-holds
  "The HARD governor holds on the ledger: a `:governor-hold` fact whose
  `:basis` names at least one governor rule. Rollout-phase holds carry
  an empty basis and a human rejection is a different fact type
  (`:approval-rejected`), so neither is counted here -- a HARD hold is
  the un-overridable kind that never reaches a human."
  [db]
  (filterv #(and (= :governor-hold (:t %)) (seq (:basis %)))
           (store/ledger db)))

(def ^:private approver-keys
  "Every key this fleet's actors have been seen to park an approver id
  under. `approver-path` probes for all of them rather than asserting
  where this repo puts it."
  [:approved-by :approver :approved-by-id :by])

(defn- approver-in [m]
  (when (map? m)
    (some (fn [k] (when-let [v (get m k)] [k v])) approver-keys)))

(defn- approver-path
  "Where (if anywhere) an approver id actually survived into `m`.
  Returns [path-vector value] or nil. Derived by probing the real map,
  never hard-coded -- if `commit-record!`/`request-approval` are ever
  fixed or broken, this page follows."
  [m]
  (or (when-let [[k v] (approver-in (:payload m))] [[:payload k] v])
      (when-let [[k v] (approver-in (:value m))] [[:value k] v])
      (when-let [[k v] (approver-in m)] [[k] v])))

(defn- verified-drift-phrases
  "The drift probes, each annotated with whether the phrase is really a
  member of `cinemaops.governor/scope-excluded-terms`, and how many
  proposals in this run actually contained it."
  [runs]
  (let [blobs (->> runs
                   (keep #(get-in % [:final-state :proposal]))
                   (mapv #(str/lower-case (pr-str %))))]
    (mapv (fn [[k label phrase]]
            {:key k :label label :phrase phrase
             :in-governor? (boolean (some #{phrase} governor/scope-excluded-terms))
             :hits (count (filterv #(str/includes? % phrase) blobs))})
          drift-phrases)))

(defn- disposition-of [run] (get-in run [:final-state :disposition]))

(defn- run-rules
  "Governor rules that actually fired on a run, from the verdict the
  governor really returned."
  [run]
  (mapv :rule (get-in run [:final-state :verdict :violations])))

(defn- escalation-reason
  "The `:reason` the real `:approval-requested` audit fact carried."
  [run]
  (->> (get-in run [:final-state :audit])
       (filter #(= :approval-requested (:t %)))
       last
       :reason))

(defn- hold-phase-reason [run]
  (->> (get-in run [:final-state :audit])
       (filter #(= :governor-hold (:t %)))
       last
       :phase-reason))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str [k] (if (keyword? k) (name k) (str k)))

(defn- sorted-kw-list
  "A set/seq of keywords rendered in a stable, sorted order."
  [ks]
  (str/join ", " (map kw-str (sort-by kw-str ks))))

(defn- sorted-map-str
  "A map rendered with its keys sorted by name -- never relying on map
  iteration order."
  [m]
  (if (or (nil? m) (empty? m))
    "—"
    (str/join " · " (map (fn [[k v]] (str (kw-str k) "=" (pr-str v)))
                         (sort-by (comp kw-str key) m)))))

(defn- td [& cells] (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [xs] (str/join "\n" xs))

(defn- ok [s] (str "<span class=\"ok\">" s "</span>"))
(defn- warn [s] (str "<span class=\"warn\">" s "</span>"))
(defn- crit [s] (str "<span class=\"critical\">" s "</span>"))
(defn- muted [s] (str "<span class=\"muted\">" s "</span>"))
(defn- code [s] (str "<code>" (esc s) "</code>"))

(defn- section [title lead & body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when lead (str "    <p class=\"muted\">" lead "</p>\n"))
       (str/join body)
       "  </section>\n"))

(defn- table [headers row-strs]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" (rows row-strs) "\n      </tbody>\n"
       "    </table>\n"))

;; --- screening directory ---

(defn- screening-status
  "The last thing that happened to this screening, read off the real
  ledger."
  [ledger sid]
  (let [f (last (filter #(= sid (:screening-id %)) ledger))]
    (cond
      (nil? f) (muted "この scenario では未接触")
      (= :committed (:t f)) (ok "committed")
      (= :approval-rejected (:t f)) (warn "人間が却下 (hold)")
      (and (= :governor-hold (:t f)) (seq (:basis f)))
      (crit (str "HARD hold · " (sorted-kw-list (:basis f))))
      (= :governor-hold (:t f)) (warn "phase hold")
      :else (muted (kw-str (:t f))))))

(defn- screening-rows [db]
  (let [ledger (vec (store/ledger db))]
    (for [s (store/all-screenings db)]
      (td (code (:screening-id s)) (esc (:film s)) (esc (:screen s))
          (esc (:venue s)) (code (:showtime s))
          (if (:registered? s) (ok "true") (crit "false"))
          (if (:verified? s) (ok "true") (crit "false"))
          (screening-status ledger (:screening-id s))))))

;; --- phase table ---

(defn- phase-rows []
  (for [p (sort (keys phase/phases))
        :let [{:keys [label writes auto]} (get phase/phases p)]]
    (td (str "<span class=\"num\">" p "</span>"
             (when (= p phase/default-phase) (str " " (muted "(default)"))))
        (esc label)
        (if (seq writes) (code (sorted-kw-list writes)) (muted "なし"))
        (if (seq auto) (code (sorted-kw-list auto)) (muted "なし")))))

;; --- governor contract ---

(defn- governor-rows []
  [(td "confidence floor"
       (str "<span class=\"num\">" governor/confidence-floor "</span>")
       (muted "これ未満の提案は自動確定させず人間へ回す"))
   (td "allowed ops (closed allowlist)"
       (str "<span class=\"num\">" (count governor/allowed-ops) "</span>")
       (code (sorted-kw-list governor/allowed-ops)))
   (td "always-escalate ops"
       (str "<span class=\"num\">" (count governor/always-escalate-ops) "</span>")
       (code (sorted-kw-list governor/always-escalate-ops)))
   (td "scope-excluded terms"
       (str "<span class=\"num\">" (count governor/scope-excluded-terms) "</span>")
       (muted "永久に禁止された決定領域を示す語句。名詞ではなく「実行・確定する行為」として書かれている(自爆回避)"))])

;; --- runs ---

(defn- disposition-cell [run]
  (let [d (disposition-of run)
        rs (run-rules run)]
    (cond
      (:awaiting? run) (warn "escalate · 人間の判断待ち (paused)")
      (and (= :hold d) (seq rs)) (crit (str "HARD hold · " (sorted-kw-list rs)))
      (and (= :hold d) (= :approval-rejected
                          (:t (last (get-in run [:final-state :audit])))))
      (warn "hold · 人間が却下")
      (= :hold d) (warn (str "hold · " (kw-str (or (hold-phase-reason run) :phase))))
      (= :commit d) (if (:human run) (ok "commit (人間承認あり)") (ok "commit (自動)"))
      :else (muted (kw-str d)))))

(defn- gate-cell [run]
  (let [rs (run-rules run)]
    (cond
      (seq rs) (crit "governor HARD")
      (hold-phase-reason run) (warn (str "phase · " (kw-str (hold-phase-reason run))))
      (escalation-reason run) (warn (str "escalate · " (kw-str (escalation-reason run))))
      :else (ok "clean"))))

(defn- run-rows [runs]
  (for [r runs]
    (td (code (:id r))
        (str "<span class=\"num\">" (:phase r) "</span>")
        (code (kw-str (get-in r [:request :op])))
        (code (get-in r [:request :screening-id]))
        (esc (:advisor-label r))
        (str "<span class=\"num\">"
             (esc (str (get-in r [:final-state :proposal :confidence] "—")))
             "</span>")
        (gate-cell r)
        (disposition-cell r)
        (muted (esc (:note r))))))

;; --- hard holds ---

(defn- hard-hold-rows [runs]
  (for [r runs
        :when (seq (run-rules r))
        v (get-in r [:final-state :verdict :violations])]
    (td (code (:id r))
        (code (kw-str (get-in r [:request :op])))
        (code (get-in r [:request :screening-id]))
        (crit (kw-str (:rule v)))
        (esc (:detail v)))))

;; --- human decisions ---

(defn- human-rows [db runs]
  (let [log (vec (store/coordination-log db))]
    (for [r runs
          :when (:paused? r)]
      (let [rec (last (filter #(and (= (get-in r [:request :op]) (:op %))
                                    (= (get-in r [:request :screening-id]) (:screening-id %)))
                              log))
            committed? (= :commit (disposition-of r))
            ap (when committed? (approver-path rec))]
        (td (code (:id r))
            (code (kw-str (get-in r [:request :op])))
            (code (get-in r [:request :screening-id]))
            (warn (kw-str (or (escalation-reason r) :escalate)))
            (cond
              (:awaiting? r) (warn "未決 (interrupt-before で停止中)")
              (= :approved (get-in r [:human :status])) (ok "承認")
              :else (crit "却下"))
            (if-let [by (get-in r [:human :by])] (code by) (muted "—"))
            (cond
              (:awaiting? r) (muted "—")
              (not committed?) (muted "commit されていない (記録なし)")
              ap (ok (str "保持 · " (code (pr-str (first ap))) " = " (code (pr-str (second ap)))))
              :else (crit "保持されていない")))))))

;; --- scope-exclusion probes ---

(defn- drift-rows [runs]
  (for [{:keys [label phrase in-governor? hits]} (verified-drift-phrases runs)]
    (td (esc label)
        (code phrase)
        (if in-governor? (ok "governor の語句表に実在") (crit "語句表に無い"))
        (str "<span class=\"num\">" hits "</span>"))))

;; --- coordination log / ledger ---

(defn- coordination-rows [db]
  (for [r (store/coordination-log db)]
    (td (code (kw-str (:op r)))
        (code (:screening-id r))
        (esc (sorted-map-str (:value r)))
        (if-let [ap (approver-path r)]
          (ok (str (esc (pr-str (first ap))) " = " (esc (pr-str (second ap)))))
          (muted "承認者なし (自動確定)")))))

(defn- ledger-rows [db]
  (for [f (store/ledger db)]
    (td (cond
          (= :committed (:t f)) (ok (kw-str (:t f)))
          (= :approval-rejected (:t f)) (warn (kw-str (:t f)))
          (seq (:basis f)) (crit (kw-str (:t f)))
          :else (warn (kw-str (:t f))))
        (code (kw-str (or (:op f) :n-a)))
        (code (or (:screening-id f) "—"))
        (code (kw-str (:actor f)))
        (if (seq (:basis f))
          (code (sorted-kw-list (:basis f)))
          (muted (if-let [pr (:phase-reason f)] (kw-str pr) "—")))
        (esc (or (:summary f)
                 (str/join " / " (map :detail (:violations f)))
                 "—")))))

;; --- approver-attribution disclosure (derived, never asserted) ---

(defn- attribution-disclosure
  "Derived at render time by walking the REAL store: where, if anywhere,
  does a human approver's id survive? Deliberately not hard-coded --
  a sibling repo in this fleet drops it in `commit-record!`, and a
  hard-coded claim here would become a lie the moment that differs."
  [db runs]
  (let [log (vec (store/coordination-log db))
        approved (filterv #(and (:paused? %) (= :approved (get-in % [:human :status]))
                                (= :commit (disposition-of %)))
                          runs)
        carrying (filterv approver-path log)
        paths (sort (distinct (map (comp pr-str first approver-path) carrying)))
        ledger-carrying (filterv approver-path (store/ledger db))]
    (str
     "    <ul>\n"
     "      <li>人間が承認して commit に至った run: <span class=\"num\">" (count approved) "</span>"
     " / 承認者 id を実際に保持している coordination-log レコード: <span class=\"num\">"
     (count carrying) "</span></li>\n"
     (if (seq carrying)
       (str "      <li>" (ok "承認者 id は SSoT レコードに残っている")
            " — 実際の格納先: " (str/join ", " (map code paths))
            "。この行はレンダリング時に実レコードを走査して導出しており、格納先が変われば追従する。</li>\n")
       (str "      <li>" (crit "承認者 id は SSoT レコードに残っていない")
            " — 承認は実際に行われたが、`commit-record!` を通過したレコードのどこにも approver key が無い。"
            "「誰も承認していない」のではなく「store が保持していない」。</li>\n"))
     "      <li>"
     (if (seq ledger-carrying)
       (str (ok "監査台帳にも承認者 id がある") " (" (count ledger-carrying) " 件)")
       (str (warn "監査台帳 (`store/ledger`) には承認者 id が無い")
            " — `:commit` ノードが書くのは `commit-fact` だけで、`:approval-granted` fact は run の"
            " `:audit` チャネルに留まり台帳へは append されない。したがって"
            " <strong>台帳だけを見ると、人間承認された commit と自動 commit は区別できない</strong>。"
            "承認の痕跡は coordination-log 側にしかない。"))
     "</li>\n"
     "      <li>" (muted "この節は demo であり、`commit-record!` / `phase-gate` の意味論はこの commit では変更していない (actor の SSoT 挙動を変えるため別 commit の仕事)。") "</li>\n"
     "    </ul>\n")))

;; ----------------------------- document -----------------------------

(defn render
  "Pure: `{:db .. :runs ..}` (as returned by `run-demo!`) -> the full
  operator-console HTML document. No clock reads, no randomness, every
  set sorted before it reaches the page."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        hs (hard-holds db)
        hard-rules (sort (distinct (mapcat :basis hs)))
        commits (filterv #(= :committed (:t %)) ledger)]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-5914 · 映画上映オペレーション Operator Console</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>映画上映活動 (ISIC 5914) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · 観客安全懸念は常に人間承認</span>\n"
     "</header>\n"
     "<main>\n"

     (section "この run の要約"
              (str "このページの全行は <code>cinemaops.render-html/run-demo!</code> が実際の "
                   "<code>cinemaops.operation</code> グラフ (langgraph StateGraph) を "
                   "<span class=\"num\">" (count runs) "</span> 回走らせた結果を、"
                   "<code>cinemaops.store</code> から読み戻したもの。手書きの行は無い。")
              (table ["指標" "値" "内訳"]
                     [(td "coordination runs" (str "<span class=\"num\">" (count runs) "</span>")
                          (muted "1 run = 1 グラフ実行 (intake → advise → govern → decide → commit | hold | approval)"))
                      (td "台帳 fact 数" (str "<span class=\"num\">" (count ledger) "</span>")
                          (muted "append-only。commit と hold の両方が残る"))
                      (td "commit" (str "<span class=\"num\">" (count commits) "</span>")
                          (muted "SSoT に書かれた提案"))
                      (td (crit "HARD governor hold")
                          (str "<span class=\"num\">" (count hs) "</span>")
                          (crit (str "人間に届かない・上書き不能。発火した rule: "
                                     (sorted-kw-list hard-rules))))
                      (td "screening directory"
                          (str "<span class=\"num\">" (count (store/all-screenings db)) "</span>")
                          (muted "<code>cinemaops.store/demo-data</code> の seed そのもの"))]))

     (section "上映ディレクトリ (SSoT seed)"
              "<code>cinemaops.store/demo-data</code> の全レコード。<code>:registered?</code> / <code>:verified?</code> は governor が提案の自己申告ではなく必ずここから再導出する。"
              (table ["screening" "作品" "スクリーン" "会場" "上映時刻" "registered?" "verified?" "この run の最終状態"]
                     (screening-rows db)))

     (section "ロールアウト phase ゲート"
              "<code>cinemaops.phase/phases</code> をそのまま表示。<code>:flag-patron-safety-concern</code> はどの phase の <code>:auto</code> 集合にも入らない — 恒久的な構造であって、まだ来ていないマイルストーンではない。"
              (table ["phase" "label" "書き込み可能な op" "自動確定できる op"] (phase-rows)))

     (section "Governor 契約"
              "<code>cinemaops.governor</code> の定数をそのまま表示。HARD チェックは 3 種 (screening 未検証 / <code>:effect</code> が <code>:propose</code> でない / scope 除外・許可 op 外) で、いずれも人間の承認で覆せない。"
              (table ["項目" "値" "内容"] (governor-rows)))

     (section "Coordination runs (この scenario)"
              "advisor 列は各 run に注入した advisor 実装。confidence は advisor が実際に返した値、gate と disposition は governor / phase gate が実際に返した判定。"
              (table ["run" "phase" "op" "screening" "advisor" "confidence" "gate" "disposition" "備考"]
                     (run-rows runs)))

     (section (str "HARD governor hold — 人間に届かない停止 (" (count hs) " 件)")
              "violation の rule と detail 文字列は governor が実際に返した値をそのまま出している。HARD hold はいかなる承認でも解除できない。"
              (table ["run" "op" "screening" "rule" "detail"] (hard-hold-rows runs)))

     (section "Scope 除外プローブ"
              "各 probe は <code>cinemaops.governor/scope-excluded-terms</code> の語句を advisor の rationale に混入させたもの。「実在」列はレンダリング時に語句表を実際に検索して確認している。R05 の観客安全懸念 (火災警報に言及) が hit 0 であることが、語句が名詞ではなく行為として書かれている理由。"
              (table ["永久に除外された決定領域" "混入させた語句 (governor の語句表より)" "語句表に実在するか" "この run で hit した提案数"]
                     (drift-rows runs)))

     (section "人間の判断 (escalation)"
              "<code>interrupt-before #{:request-approval}</code> でグラフが停止し、人間が再開させた run。最終列は「承認者 id が実際に SSoT レコードに残ったか」をレンダリング時に走査して導出したもの。"
              (table ["run" "op" "screening" "escalation 理由" "人間の判断" "承認者" "承認者 id の保持先"]
                     (human-rows db runs)))

     (section "承認者の帰属 (実測)"
              nil
              (attribution-disclosure db runs))

     (section "確定した coordination log (SSoT)"
              "<code>cinemaops.store/coordination-log</code> — commit ノードだけが書ける。"
              (table ["op" "screening" "value" "承認者"] (coordination-rows db)))

     (section "監査台帳 (append-only)"
              "<code>cinemaops.store/ledger</code> — 提案が commit されたか hold されたか、どの根拠で、誰の名前で。"
              (table ["fact" "op" "screening" "actor" "basis / phase 理由" "summary / detail"]
                     (ledger-rows db)))

     "</main>\n"
     "<footer>\n"
     "  <p>生成: <code>clojure -M:dev:render-html</code> (<code>cinemaops.render-html</code>)。"
     "実 actor 実行から build 時に生成される決定論的な成果物 — タイムスタンプも乱数も含まないため、同じ seed に対して再実行するとバイト単位で同一になる。</p>\n"
     "  <p>この actor は観客安全当局の判断 (避難命令の無効化・年齢確認の無効化) を確定させず、映写設備・防火設備を直接操作しない。"
     "これは段階的ロールアウトの未実装項目ではなく、charter による恒久的な除外。</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (hard-holds db)]
    (when (empty? hs)
      (throw (ex-info "no governor hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (let [html (render result)]
      (spit out html)
      (println "wrote" out
               (str "(" (count html) " bytes, " (count runs) " runs, "
                    (count (store/ledger db)) " ledger facts, "
                    (count hs) " HARD holds, rules "
                    (sort (distinct (mapcat :basis hs))) ", "
                    (count (store/coordination-log db)) " committed records)")))))
