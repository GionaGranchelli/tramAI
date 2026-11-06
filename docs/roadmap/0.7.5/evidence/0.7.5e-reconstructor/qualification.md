# 0.7.5e — Side-Effect-Free Historical Reconstructor

Implements the 0.7.5d contract by reading the evidence that already exists. It reads two things and
classifies them: nothing else is reachable from it.

## Scope

Added, in `tramai-security` (`dev.tramai.security.reconstruction`):

- `HistoricalConfigurationSource` — a `fun interface` returning the historical
  `HistoricalConfigurationReference` for a run. The seam the 0.7.5d trust boundary requires:
  implementations read **persisted historical evidence**, and `NotRecorded` is always the correct
  answer when it is not there.
- `EvidenceBackedReconstructor` — `reconstruct(workflowRunId, governanceScope?)` returns a
  `ReconstructionResult`.

Unchanged: `ReconstructionRequest`, `ReconstructionResult`, the status and limitation vocabulary, the
0.7.5c timeline and its query, the governance query, the attempt store. No new persistence, no engine
loop, no scheduling, no HTTP surface.

## Why it is side-effect-free

Its only collaborators are `SemanticTimelineQuery` (a read) and `HistoricalConfigurationSource` (a
read). There is no provider client, tool executor, approval service, audit writer or network client
among its fields, so no external action can be invoked from it — the capability is absent, not merely
unused. `no collaborator can perform an external action` asserts exactly that: the declared field types
are only those two, and the class declares no method other than `reconstruct`.

## The trust boundary, operational

The reconstructor builds the `ReconstructionRequest` itself and takes the historical reference from
`HistoricalConfigurationSource`. A caller **cannot** supply a reference: `reconstruct` accepts a run id
and an optional governance scope, nothing else. So the 0.7.5d rule — obtain references from persisted
historical evidence, never from current configuration — is enforced at the call boundary rather than
asked of the caller.

What this cannot do is verify a source implementation. A source backed by current configuration is
invisible to this class; the obligation is documented on the port, and `NotRecorded` is the only
value the contract treats as "no historical identity".

## Invariants and the test that holds each

| Invariant | Test |
|---|---|
| The reference comes from persisted evidence, not the caller | `the historical reference comes from the source, not from the caller` |
| A source that recorded nothing is a limitation, not a substitution | `a source that recorded nothing yields a limitation, never a substituted value` |
| One read per source, for the requested run | `each collaborator is read exactly once, with the requested run` |
| The governance scope reaches the read unchanged | `the governance scope reaches the timeline read unchanged` |
| A foreign scope is refused before anything is read | `a scope naming another run is refused before anything is read` |
| Uninstrumented history is never zero attempts | `uninstrumented history is never turned into zero attempts` |
| A refused projection is reported, not repaired | `a refused projection is reported as refused, not repaired` |
| A store failure propagates; it does not become "no evidence" | `a store failure propagates instead of being reported as absent evidence` |
| No external action is reachable | `no collaborator can perform an external action` |
| Reconstruction is repeatable | `reconstruction of the same evidence is repeatable` |

Discrimination recorded (mutants applied to the implementation, then restored):

```
reference taken as NotRecorded instead of from the source
  -> the historical reference comes from the source, not from the caller     FAILS
governance scope dropped when building the request
  -> the governance scope reaches the timeline read unchanged                FAILS
  -> a scope naming another run is refused before anything is read          FAILS
store failure swallowed and converted into an uninstrumented timeline
  -> a store failure propagates instead of being reported as absent evidence FAILS
```

## Verification

| Check | Result |
|---|---|
| `:tramai-security:test --tests 'dev.tramai.security.reconstruction.*'` | 27 tests (17 contract + 10 reconstructor), 0 failures |
| whole-module and CI results | recorded at the head that opened the PR |
| `spotlessCheck`, `verifyStaticAnalysis`, `verifyChangePolicy`, `apiCheck`, `verify060Architecture`, `verifyCancellationSafety`, `verifyJUnitTestSignatures`, `verifyCompilerWarnings` | see the post-commit report |

## Deliberately not here

- **No default `HistoricalConfigurationSource`.** A built-in implementation would have to guess how to
  find a run's persisted evidence (the audit store is stream-addressed, not run-addressed), and the
  wrong default is precisely the current-configuration substitution the contract forbids. The port has
  no implementation in this library; the caller supplies one over the store it already owns.
- **No audit-stream scanner.** `AuditStore.readStream` is per stream id, and mapping a run to its
  stream ids is application policy (`AuditStreamIdResolver` takes a `PolicyContext`). Guessing proves
  nothing; when a run-addressed reader exists, it becomes one obvious implementation of the port.
- **No report assembly, export, retention, paging or cross-run aggregates** — 0.7.5f+ if ever needed.
- **No content.** Same honest limit as 0.7.5d: no dedicated payload field exists; the string values the
  surface carries are opaque and are not redacted or verified here.
- **Inherited gaps unchanged:** `finishReasonReported` stays `false` (0.7.5b); a default Spring
  application records no attempts and therefore reconstructs as `ATTEMPTS_UNINSTRUMENTED` (0.7.5b).
