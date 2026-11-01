# 0.7.6g — Enforcement-origin attestation contract (Issue #524)

Status: **contract + threat model only. No implementation until the authority boundary below is
ratified.**
Base: `release/0.7.0` at `212706c7`. Owner decisions required: §7.

## 1. The claim under review

`tool.permission` evidence carries, for a declared boundary, two metadata keys:
`boundary.id` and `boundary.enforcementStrength`. `AUTHORITATIVE` is reachable **only** when the
declared id resolves to a framework-owned registration (`RuntimeBoundaryProvenance.registered`).

The question is not whether strength is derived rather than asserted — it is, and that is proven.
The question is whether **naming** a registered boundary is evidence that the boundary
**emitted or enforced** the event.

## 2. Established facts at `212706c7` (VERIFIED by reading the code)

| # | Fact | Site |
| --- | --- | --- |
| F1 | `boundary.enforcementStrength` supplied by a caller is refused before derivation | `RuntimeBoundaryProvenance.provenanceFor` |
| F2 | `boundary.id` is read from **caller-controlled** `AuditEvent.metadata`, **before** the family allowlist is applied, and `boundary.id` is not in that allowlist | `ToolPermissionRuntimeEvidenceExporter.toToolPermissionRuntimeEvidenceRecord` |
| F3 | `AuditEvent` is a public data class with a public constructor; `AuditEngine.emit(AuditEmission)` lets the caller choose `enforcementPoint`, `decision`, `policyVersion` and `metadata` | `AuditEvent.kt`, `AuditEngine.kt` |
| F4 | `ToolPermissionRuntimeEvidenceExporter.export(List<AuditEvent>)` is public, accepts any list, and never reads the store | exporter L41 |
| F5 | The append path computes a hash chain (`eventHash` / `previousEventHash`); the exporter ignores both fields entirely | `AuditEngine.emit`, exporter |
| F6 | **No production implementation of the registered boundary exists.** `spring-ai-tool-callback` appears only in the registration table, the KDoc, and tests | `grep -rn GovernedToolCallback --include=*.kt tramai-*/src/main` → 0 hits |
| F7 | The gap is already documented and pinned: `ToolPermissionBoundaryProvenanceTest.the registration does not verify the origin of a declared identifier` — renamed in 0.7.6g1 to `a fabricated event naming a registered boundary cannot claim authoritative enforcement` | test L175 |

**Consequence (INFERRED, mechanically followable):** any code that can construct an `AuditEvent` —
or call `AuditEngine.emit` — can produce a record that reads
`boundary.id=spring-ai-tool-callback`, `boundary.enforcementStrength=AUTHORITATIVE`,
`source.component=policy-engine`, and a digest that binds all of it. Nothing in the evidence path
distinguishes that record from one produced by a boundary that really consulted the engine. The
strongest-looking claim in the evidence model is therefore reachable from data alone.

## 3. Threat model

**Assets.** (a) the truth of "this action was prevented by a governed boundary"; (b) an auditor's
ability to distinguish prevention from self-report; (c) the digest binding (already correct — a
fabricated record is *internally* consistent, which is exactly why digests do not help).

**Adversary A1 — in-process component with data access (the realistic case).** A plugin, adapter or
tool implementation running in the host JVM builds an `AuditEvent` (F3) with a registered
`boundary.id` and an `ALLOW`/`DENY` decision, hands it to `export` (F4), and the resulting record
claims AUTHORITATIVE prevention that never happened. No privilege escalation needed: the classes are
public and the entry point takes a list.

**Adversary A2 — caller of the legitimate producer.** `AuditEngine.emit` accepts caller metadata
(F3), so the same fabrication runs through the *sanctioned* writer, producing a chain-valid event.
Chain validity (F5) does not discriminate, because the exporter never checks it, and because A2's
event is genuinely appended.

**Adversary A3 — out-of-process writer.** Not in scope here: out-of-runtime evidence is `IMPORTED`
by definition and must not be promotable to AUTHORITATIVE by inspection of its own bytes. Recorded
so the contract does not silently cover it.

**Adversary A4 — the boundary itself lies.** A registered boundary that does not consult the engine
but names itself anyway. Prevention tests (the registration's `preventionProof`) are the only
mechanism that addresses this, and they are tests, not runtime attestation.

**Non-goals.** Preventing arbitrary code execution inside the JVM from rewriting the audit stream:
whoever can rewrite the store owns the evidence, whatever the schema says. This must be stated in
the contract as a limit rather than implied away.

## 4. Who can be trusted to attest enforcement — the decision that must be made

A registered id is not origin. Four candidate trust roots, with the honest reason each holds or
fails:

| Root | Attester | Why it does or does not hold |
| --- | --- | --- |
| **R1 — event data** (today) | none | Rejected by F2/F3: the declaration is caller data. This is the defect. |
| **R2 — append path / chain** | the trusted writer of the audit stream | Closes A1 only if the exporter *reads back* through the store and verifies the chain. Does **not** close A2: the engine accepts caller metadata, so the fabricator appends a valid event. Necessary, not sufficient. |
| **R3 — wiring-held capability** | the framework-owned boundary instance | The exporter requires a value that the framework mints at bean construction and injects into the boundary only; the value never travels in event metadata. Closes A1 and A2 against ordinary data-path forgery, because producing the value requires holding the boundary instance. Ceiling: same-JVM reflection can reach any object — an in-process authority statement, not a cryptographic one. |
| **R4 — cryptographic signature / workload identity** | a key or identity the fabricator cannot obtain | Only stronger than R3 if key custody is outside the process (KMS/HSM, mTLS identity). In-JVM key material is reachable by the same code, so signing *inside* the process closes nothing — exactly the clause in the review. Cost: key management, rotation, verification surface, cross-deployment trust. Not justified while F6 holds (no boundary implementation exists to sign). |

**Recommended: R3 as the authority, with R2 as a supporting check and an explicit classification for
everything else.** Concretely:

1. A record may carry `AUTHORITATIVE` **only** when the enrichment came from an attester the
   framework constructed (R3), never from event metadata. The metadata key stays readable for
   diagnostics, but it is no longer what grants strength.
2. Every record without R3 attestation that names a boundary is **classified, not rejected as
   corruption**: `boundary.origin = declared` (an honest "somebody said so"), and its strength is
   capped below AUTHORITATIVE. Rejection over downgrade stays the rule for *unregistered* ids and
   *self-asserted* strength (F1), which are malformed, not merely unproven.
3. `IMPORTED` remains the classification for out-of-runtime evidence (A3) and is never promotable
   by inspection.
4. No policy decision is made anywhere in this path. It adds a provenance gate, not a second policy
   engine.

**Why R3 and not R4 now:** R4 is only meaningful with out-of-process custody, and F6 shows there is
no boundary implementation to hold a key. Building key infrastructure to protect a claim that no
production component currently makes is the speculative case. Revisit R4 when a real boundary ships
or when evidence crosses a deployment boundary (which is also when A3 becomes live).

## 5. Bounded implementation plan (two slices, nothing else)

**Slice 1 — split declaration from attestation (the contract change).**
- `RuntimeBoundaryProvenance` gains an attester-sourced path: strength is granted only by a value the
  module mints; `provenanceFor(eventMetadata, enforcementPoint)` keeps its signature but can no
  longer return `AUTHORITATIVE` from metadata alone.
- New record field `boundary.origin` ∈ {`attested`, `declared`} — two legal values, validated
  atomically with the existing pair (zero keys = legacy, one key = corruption, three or four keys
  consistent). AUTHORITATIVE + `declared` is refused.
- **Boundary of the change:** `RuntimeBoundaryProvenance`, the exporter's overlay, the contract
  validator's metadata rule, and the digest (it already folds metadata, so no digest change is
  needed — but the digest must be recomputed and its existing corpus regenerated, which is a
  migration cost, not a code change).
- Deliberately **not** in Slice 1: reading back from the store (R2), key material (R4), any change to
  `source.component`.

**Slice 2 — make the attester real (only when a boundary implementation exists).**
- The framework mints the attester at wiring and injects it into the governed boundary; the boundary
  passes it when emitting.
- Exporter additionally verifies chain continuity when the event came from the store (R2), so an
  event that was never appended cannot be attested.
- Blocked on F6: there is nothing to wire it into yet. Slice 2 lands with the first real boundary,
  not before.

## 6. Discriminating tests (each must die when its guard is removed)

| # | Test | Forbidden behaviour it excludes |
| --- | --- | --- |
| T1 | `a fabricated event naming a registered boundary cannot claim authoritative strength` — build `AuditEvent` with `boundary.id=spring-ai-tool-callback`, export, assert strength is not AUTHORITATIVE and `boundary.origin=declared` | A1, the issue's core case |
| T2 | `an attested event from the framework attester carries authoritative strength` — positive control through the attester path | over-correction: legitimate flows must survive |
| T3 | `a record claiming authoritative strength with declared origin is refused by the contract validator` | a caller editing the serialized record after export |
| T4 | `an unregistered boundary id is still rejected, not downgraded` — keeps the existing refusal semantics (regression guard) | conflating "unproven" with "malformed" |
| T5 | `the digest binds boundary.origin` — two records differing only in origin have different payload digests | metadata that reaches evidence without participating in its digest |
| T6 | `imported evidence cannot be promoted by inspection` | A3 |

Mutations to run for each: remove the origin cap (T1), drop origin from the digest (T5), allow
`declared` + AUTHORITATIVE in the validator (T3), downgrade unregistered ids instead of refusing (T4).

## 7. Owner decisions required before implementation

1. **Authority root:** accept R3 (wiring-held in-process capability, documented ceiling) over R4
   (out-of-process key/identity), or require R4 now?
2. **Unproven-but-well-formed evidence:** classify as `declared` and cap strength (recommended), or
   reject the record outright? The recommendation keeps legitimate declaration-only integrations
   working; outright rejection would break any producer that today names a boundary without an
   attester — which, per F6, is *every* producer.
3. **Slice 2 timing:** hold the store Read-back (R2) until a real boundary exists (recommended, F6),
   or implement it against a test-only boundary now?

## 8. Ratified decisions and Slice 1 as built (0.7.6g1)

Owner ruling: **R3 approved** (wiring-held in-process capability, in-process ceiling documented),
**classify-and-cap approved**, **Slice 2 deferred** until a real boundary exists and can prove that
evaluation and prevention happen where attribution is established.

Shipped in Slice 1:

- **Vocabulary.** `boundary.enforcementStrength` gains `DECLARED`. It is deliberately *not* `OBSERVED`:
  observation is a claim about the boundary's own behaviour, and a declaration makes no such claim.
  `AUTHORITATIVE` remains the registration's **entitlement**, not something this path may report.
- **Second axis.** `boundary.origin` ∈ {`declared`, `attested`}. Reserved, validated atomically with the
  other two keys (zero = legacy, three = governed, anything between = corruption). `attested` is
  **refused** until an attester exists, so the value cannot be reached by editing bytes either.
- **Data-only export declares.** `provenanceFor` emits `{id, DECLARED, declared}` for a registered
  identifier, and refuses a caller-supplied strength *or* origin as self-assertion. The registration's
  entitlement is never copied into a record from data.
- **Verifier parity.** `examples/sovereign-lab/verify-evidence-bundle.sh` carries the same three-key
  tuple, the same closed vocabularies, the same attested refusal and the same declared-strength cap —
  the out-of-process reviewer and the framework agree, so a bundle cannot be "valid" under one and not
  the other.
- **Consequence for the demo boundary.** `GovernanceBoundaryTest` still proves the Spring AI boundary
  prevents execution, while its *evidence* now declares. Behavioural strength and evidence strength are
  separate facts, and the tests pin both sides of that separation.

Mutation proof (each removes one guard; the named test must die): declaration replaced by the
entitlement → fabrication test dies; declared origin claiming a higher strength → cap test dies;
`attested` accepted → attester test dies; caller-supplied origin copied → self-assertion test dies;
origin dropped from the reserved tuple → pre-g1 two-key test dies.

**Issue #524 stays open**: the exporter can no longer emit fabricated AUTHORITATIVE evidence, but
genuine origin attribution requires the Slice 2 attester.

## 9. Historical bundles written before 0.7.6g1

A pre-g1 `runtime-evidence.v1` record carries a **two-key** provenance tuple (`boundary.id` plus
`boundary.enforcementStrength`). Under the current policy that tuple is *partial provenance* and is
rejected. That is a **validation-policy change, not evidence corruption**, and the two must stay
distinguishable.

- **Sealed bundles are never rewritten.** Editing history to satisfy a new rule would destroy exactly
  the property the evidence exists to provide.
- **`Runtime-evidence.v1` two-key provenance was never published as a supported external contract.**
  It shipped only on `release/0.7.0` while the release is still uncertified; there is no external
  consumer whose bundle is entitled to the old reading. (STATUS: INFERRED from the release state; if a
  downstream consumer is ever declared, this paragraph is the one to revisit.)
- **Historical verification is preserved explicitly.** `verify-evidence-bundle.sh --legacy-provenance`
  reads a two-key tuple, still checks it against the registration and the enforcement point, and emits
  a warning stating that the strength is *registration-derived, not attested, origin unknown*. Default
  mode keeps refusing it.
- **Legacy reading never confers authority.** A legacy record's `AUTHORITATIVE` string is not evidence
  of prevention and is not upgraded, capped-and-hidden, or re-signed. It is reported for what it is.

Mutation proof for the legacy rule (each removes one guard from the verifier): removing the legacy
branch kills the read-back test; granting legacy reading without the flag kills it too; removing the
registry lookup from the legacy branch kills the unregistered-in-legacy-mode assertion. **The third
mutation initially reported SURVIVED — that was a harness defect, not a guard**: the shell verifier is
not a Gradle task input, so an up-to-date `test` task returned success without executing anything.
Re-run with `--rerun-tasks`, it kills. Any future mutation over this verifier must force execution.

## 10. What this contract does not claim

- It does not make fabricated evidence impossible inside a JVM. It makes AUTHORITATIVE
  unreachable from event data, and labels everything else honestly.
- It does not establish that the registered boundary prevents anything at runtime, beyond the
  prevention tests the registration cites (A4).
- It does not change `source.component` pinning, the identity tuple, or the frozen surfaces
  S18/S19.
