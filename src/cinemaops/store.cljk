(ns cinemaops.store
  "SSoT for the ISIC-5914 motion-picture-projection (cinema/theater
  exhibition) COORDINATION actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every
  `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a cinema/theater
  exhibiting motion pictures to the public: showtime/attendance/print-
  quality logging, showtime/screen-allocation scheduling proposals,
  digital-cinema-package (DCP)/print delivery coordination, and
  patron-safety-concern flagging (fire/egress, disturbance,
  age-rating-admission concerns). It NEVER directly finalizes a
  patron-safety-authority decision (evacuation override, age-rating
  admission override) and NEVER directly actuates projection/booth
  equipment or fire/life-safety systems -- see `cinemaops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable
  block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/
  demo (no deps). A `screenings` directory keyed by `:screening-id`
  STRING (never a keyword -- consistent keying from the start,
  avoiding the silent-miss bug that plagued an earlier shepherd
  attempt).

  A registered/verified screening record must exist before ANY
  proposal for that screening may ever commit or escalate --
  `cinemaops.governor`'s `screening-unverified-violations` re-derives
  this from the screening's own `:registered?`/`:verified?` fields,
  never from proposal self-report, the SAME 'ground truth, not
  self-report' discipline every sibling actor's own governor uses.

  The ledger stays append-only: which screening a proposal targeted,
  which operation, on what basis, committed/held/escalated and
  approved by whom is always a query over an immutable log.")

(defprotocol Store
  (screening [s screening-id] "Registered screening record, or nil.
    Screening map: {:screening-id .. :film .. :screen .. :venue ..
    :showtime .. :registered? bool :verified? bool}.")
  (all-screenings [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-screenings [s screenings] "replace/seed the screening directory (map screening-id->screening)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained screening directory covering both the happy
  path and the governor's own hard checks, so the actor + tests run
  offline."
  []
  {:screenings
   {"screening-1" {:screening-id "screening-1" :film "The Long Reel (2026)"
                    :screen "Screen 3" :venue "Riverside Cinema" :showtime "2026-07-16T19:00"
                    :registered? true :verified? true}
    "screening-2" {:screening-id "screening-2" :film "Midnight Frame"
                    :screen "Screen 1" :venue "Riverside Cinema" :showtime "2026-07-16T21:30"
                    :registered? true :verified? true}
    "screening-3" {:screening-id "screening-3" :film "Silent Aperture"
                    :screen "Screen 5 (new, post-renovation)" :venue "Riverside Cinema" :showtime "2026-07-17T18:00"
                    :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (screening [_ screening-id] (get-in @a [:screenings screening-id]))
  (all-screenings [_] (sort-by :screening-id (vals (:screenings @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-screenings [s screenings] (when (seq screenings) (swap! a assoc :screenings screenings)) s))

(defn seed-db
  "A MemStore seeded with the demo screening directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `screenings` map (screening-id
  string -> screening map) -- the primary test/dev entry point.
  `screenings` may be empty (an unregistered-everywhere store)."
  [screenings]
  (->MemStore (atom {:screenings (or screenings {}) :ledger [] :coordination-log []})))
