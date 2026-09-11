# cloud-itonami-isic-5914

**Motion Picture Projection Activities** — ISIC Rev.4 class 5914.

A coordination-only actor for cinema/theater exhibition operations, behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: log-screening-record, schedule-screening-operation, coordinate-print-delivery, flag-patron-safety-concern (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Screening verified** — target screening must exist AND be registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — this actor NEVER directly finalizes a patron-safety-authority decision (an evacuation override — e.g. deciding to keep the theater open during an evacuation alarm — or an age-rating admission-check override) and NEVER directly actuates projection/booth equipment or fire/life-safety systems. These are permanently, structurally blocked, regardless of confidence, op, or human approval — see CRITICAL scope exclusions below.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: screening-record logging only (approval-gated)
  - Phase 2: + schedule-screening-operation, coordinate-print-delivery (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals (patron-safety concerns always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## CRITICAL — scope exclusions

This actor is a back-office **operations coordination** actor only. It is **NOT** a
patron-safety-authority and **NOT** a projection-equipment controller. It never
performs or authorizes:

- Finalizing a patron-safety-authority decision — an evacuation override (e.g. the
  decision to keep the theater open during a fire/evacuation alarm) is always either
  a hard, permanent block (if the advisor attempts to finalize it) or handled entirely
  outside this actor by the real safety authority (fire marshal / venue management on
  scene). This actor may only *log an observation* via `flag-patron-safety-concern`,
  which always escalates to a human and can never auto-commit.
  Cinema/theater operations have a direct patron-safety dimension (fire/egress,
  age-rating enforcement for admission) — the closed op allowlist deliberately
  contains no op that itself finalizes such a decision.
- Finalizing an age-rating admission-check override — a decision to admit a patron
  despite an age-rating restriction is always a hard, permanent block.
- Directly actuating projection/booth equipment (starting/stopping the projector,
  applying a DCP decryption key, operating the digital cinema server).
- Directly controlling fire/life-safety systems (fire alarms, sprinkler/suppression
  systems, fire doors).

`flag-patron-safety-concern` is the only op through which this actor may ever touch
patron-safety territory, and it **always** requires human sign-off — it is
structurally absent from every rollout phase's `:auto` set, including phase 3
(two independent layers agree on this: `cinemaops.governor/always-escalate-ops`
and `cinemaops.phase/phases`).

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/cinemaops/governor_test.cljk` — unit tests of governor hard checks and scope exclusion (including a dedicated regression test that the default mock advisor's own proposals never self-trip scope-exclusion)
- `test/cinemaops/advisor_test.cljk` — advisor proposal shape and consistency
- `test/cinemaops/phase_test.cljk` — rollout phase logic
- `test/cinemaops/governor_contract_test.cljk` — full graph integration, audit trail
- `test/cinemaops/store_contract_test.cljk` — Store protocol and MemStore implementation

## Modules

- `cinemaops.store` — SSoT (MemStore, String-keyed screening directory, append-only ledger)
- `cinemaops.advisor` — contained intelligence node (mock + real-LLM seam)
- `cinemaops.governor` — independent compliance layer
- `cinemaops.phase` — staged rollout (0→3)
- `cinemaops.operation` — langgraph-clj StateGraph
- `cinemaops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-facing/personal-services)
fleet. See ADR-2607121000, ADR-2607152500, and the paired
`90-docs/adr/*-cloud-itonami-isic-5914-motion-picture-projection-coverage.md`/`.edn`
ADR in the `com-junkawasaki/root` superproject for design decisions.
