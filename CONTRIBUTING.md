# Contributing to cloud-itonami-isic-5914

Contributions should preserve the actor's scope: back-office cinema/theater
exhibition operations coordination only, with CRITICAL exclusions of any op that
directly finalizes a patron-safety-authority decision or directly actuates
projection/booth or fire/life-safety equipment (see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: kbb -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Finalize a patron-safety-authority decision (an evacuation override — e.g. keeping
  the theater open during a fire/evacuation alarm — or an age-rating admission-check
  override). These are always either a hard, permanent block or an always-escalate
  op (`flag-patron-safety-concern`), never auto-commit-eligible at any phase.
- Directly actuate projection/booth equipment (start/stop the projector, apply a DCP
  decryption key, operate the digital cinema server).
- Directly control fire/life-safety systems (fire alarms, sprinkler/suppression
  systems, fire doors).

Contributions that cross these boundaries will be rejected.
