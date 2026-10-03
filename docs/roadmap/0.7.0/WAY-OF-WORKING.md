# TramAI 0.7.0 — Way of Working

## 1. Branch authority

### `master`
Represents the stable/released line. During 0.7 development it accepts 0.6.x bug/security/release-maintenance work, not unfinished 0.7 features.

### `release/0.7.0`
Authoritative 0.7 integration branch. It contains the complete execution board, Epic specs, accepted implementation, release evidence, and release-facing documentation.

### `epic/0.7.x-*`
One branch per Epic, created from the current `release/0.7.0` head. Epic branches own implementation and proof for exactly one Epic.

### task/candidate branches
Short-lived branches created from an Epic branch for independently reviewable candidates. They merge back into that Epic branch.

## 2. PR direction

```text
task/candidate → epic/0.7.x → release/0.7.0 → master
```

No 0.7 task/Epic PR targets `master` directly.

## 3. Integration discipline

- Start independent Epics from the same release baseline when useful.
- Do not force artificial independence once a real dependency exists.
- Before an Epic PR is integrated, rebase it onto the current `release/0.7.0` head.
- Integrate completed Epics progressively; do not keep eight giant branches until release day.
- Stable fixes moving on `master` are periodically merged/rebased **into** `release/0.7.0`; 0.7 work moves back to `master` only at final promotion.
- A release-branch sync never silently changes an Epic contract. If it does, reopen the affected spec/acceptance proof.

## 4. Spec-first rule

Every Epic starts with its committed spec before material implementation. Each task/candidate must map to a numbered item in the Epic spec or explicitly amend that spec first.

The minimum spec contract is defined in [`SPEC-TEMPLATE.md`](SPEC-TEMPLATE.md).

## 5. Candidate protocol

For correctness/security-sensitive changes use:

```text
derive → characterize → implement smallest change → prove
      → adversarial proof → mutation proof where valuable → integrate
```

A candidate is not complete merely because the happy path passes.

## 6. Definition of ready — Epic

An Epic may enter `Active` only when:

- objective and operator/user outcome are explicit;
- scope/non-goals are explicit;
- current baseline is characterized;
- authority boundaries are named;
- invariants and failure semantics are written;
- dependencies are classified as HARD/SOFT/NONE;
- candidate/task decomposition exists;
- acceptance and adversarial proof are defined.

## 7. Definition of done — task/candidate

A task is complete when:

- implementation matches the spec;
- focused tests pass;
- required architecture/API/TCK checks pass;
- adversarial discriminator exists for high-value semantics;
- mutation evidence exists when a plausible semantic mutation would otherwise survive;
- docs/evidence are updated;
- no unrelated scope is bundled.

## 8. Definition of done — Epic

An Epic is complete when:

- every required task is complete or explicitly removed from scope by spec change;
- Epic acceptance criteria are green;
- Epic invariants have deterministic proof;
- compatibility/security/telemetry requirements are satisfied;
- exact-head repository verification required by [`QUALITY-GATES.md`](QUALITY-GATES.md) is green;
- Epic docs reflect implementation, not aspiration;
- the Epic branch is rebased onto current `release/0.7.0` and its integration PR is green.

## 9. Definition of done — release

The release branch is promoted only after exact-head certification. The final PR to `master` must introduce no new functional integration work.

## 10. Scope control

Any new 0.7 work must answer the release-cut questions:

1. Which missing edge in the P0 control-plane loop does it complete?
2. Is 0.7 unsafe or incoherent without it?
3. Why is preserving an architecture boundary insufficient?
4. Why can implementation not move to 0.8.0 or later?

Weak answers mean defer.

## 11. Review rules

Reviewers should challenge, in order:

1. **Authority:** which component owns truth and mutation?
2. **Safety:** can stale, missing, reordered, retried, concurrent, or malicious input widen authority?
3. **Evidence:** can the decision later be explained/reconstructed without inventing facts?
4. **Compatibility:** does this accidentally hard-code a later 0.8/0.9/0.10 surface?
5. **Proof:** would a plausible wrong implementation still pass the tests?

## 12. Branch naming

Recommended:

```text
release/0.7.0
epic/0.7.1-control-plane-authority
epic/0.7.2-policy-trust-zones
...
task/0.7.1a-baseline-audit
task/0.7.1b-workload-identity-contract
```

Existing repository conventions may be used where automation requires them, but the Epic/task ID must stay discoverable.
