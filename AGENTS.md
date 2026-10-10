# AGENTS.md

This repository contains Tramai, a structured-first, observability-native AI workflow library for the JVM.

Tramai exists to let backend engineers add AI capabilities to existing JVM applications through typed interface methods rather than chain-oriented or agent-oriented abstractions. The project is Kotlin-first, Java-friendly, framework-agnostic in its core, and designed for strong observability and strong testability.

## Project Purpose

Tramai should be the library a JVM backend engineer reaches for when they want:

- one annotated interface as the primary AI abstraction
- typed inputs and typed outputs
- structured output as the default contract for non-`String` results
- explicit module boundaries
- OpenTelemetry-friendly observability
- framework-free usage, with optional framework adapters

The codebase should reflect that purpose. Avoid adding abstractions that push Tramai toward agent frameworks, prompt-template-heavy APIs, or hidden orchestration models.

## Non-Negotiable Design Principles

- Typed contracts over raw prompt plumbing in application code
- Framework-agnostic core, thin adapters
- Structured output is a first-class capability, not an add-on
- Observability is important, but optional at the dependency level
- Fail loudly and with context when correctness cannot be guaranteed
- Prefer explicitness over magical behavior when the API surface or module boundaries are at stake

## Architectural Guardrails

Contributors and coding agents must preserve the current architectural decisions documented under `docs/adr/`.

Especially important:

- `tramai-engine` owns orchestration and retry policy
- `tramai-structured` owns schema generation, extraction, deserialization, and structured failure analysis
- `tramai-standalone` remains minimal
- `tramai-observability` remains optional and opt-in at the dependency level
- provider resolution is registry-based, not driven by fragile model-prefix heuristics
- blocking Java support in v1 uses explicit blocking service interfaces, not invented `*Blocking` methods

If a change pressures one of these boundaries, update or add an ADR before implementation drifts.

## Quality Bar

Tramai is a library. Library bugs become downstream application bugs. The standard is therefore higher than "works in one example."

Every change should aim for:

- coherent API design
- explicit failure modes
- deterministic behavior
- narrow, well-defended module boundaries
- test coverage that proves the intended behavior

Do not accept vague correctness. If behavior matters, assert it.

## Testing Standard

Tests are not ornamental in this repository. They are part of the design audit trail.

Every meaningful behavior change should come with tests that:

- prove the happy path
- prove the failure path
- prove boundary behavior where modules interact
- prove assertions about retries, validation, and exceptions where relevant

When adding or changing code, prefer tests that verify externally visible behavior over tests that mirror implementation internals.

Examples of the expected rigor:

- proxy dispatch tests should assert exact routing behavior
- structured output tests should assert parse success, parse failure, retry triggering, and terminal exception payloads
- provider tests should assert deterministic routing, timeout handling, retry behavior, and error mapping
- observability tests should assert span attributes and parse-failure events, not just that tracing code executed

For governance and authority machinery (mutation ratchets, population admissions, classification
enrollments, baseline growth gates), the test hierarchy is different, and a single level is never
enough:

- **pure verifier tests prove semantics** — the rule is correct for the inputs it is handed;
- **real-task tests prove authority transport** — the Gradle task actually loads the repository
  state (ledger, baseline, configuration) that the verifier assumes, and changing that real file
  changes the task's outcome.

Both are required. An authority system can be internally correct and still be ineffective because
the wrong state reaches it; a verifier-level discriminator cannot detect a missing
`Loader.load(rootDir)` at the call site.

## Assertions and Invariants

Write code that defends its invariants early and clearly.

Prefer:

- explicit validation of unsupported service definitions
- explicit resolution errors for unknown providers or models
- explicit exception payloads with enough context for debugging
- explicit assertions in tests for contract-level behavior

Avoid:

- silent fallback behavior
- hidden cross-module coupling
- tests that only check that "nothing crashed"
- shallow tests that assert non-null when the important question is semantic correctness

## Auditability

Code should be easy to audit by reading tests and public contracts together.

That means:

- names should be precise
- exceptions should communicate cause and context
- module responsibilities should be visible from APIs
- test names should describe the audited behavior

If a reviewer cannot tell from the tests what a feature guarantees, the test suite is not strong enough yet.

## Repository Navigation

Before editing a subsystem:

1. Read [ARCHITECTURE.md](./ARCHITECTURE.md).
2. Follow its ownership pointer to the relevant module.
3. Read the applicable guide under `docs/architecture/change-guides/` when one exists.
4. Run the contract/TCK identified by the architecture documentation.

The architecture map is authoritative for navigation; do not duplicate it here.

## Mandatory Execution Protocol

### Before editing

1. **Classify the primary change** — pick one primary class plus any supporting:
   - `runtime-behaviour` — production logic, tests, internal refactoring (primary)
   - `public-api` — annotation surface, public contracts, SPI
   - `build-logic` — scanners, analyzers, Gradle plugins, baselines
   - `canonical-baseline` — updating `config/quality/0.6.0-baseline.json`
   - `quality-deviation` — adding/modifying `config/quality/maintainability-deviations.yml`
   - `ci-workflow` — `.github/workflows/**`
   - `documentation` — docs, comments, AGENTS.md, task descriptions
   - `baseline-migration` — scanner identity, schema, or cardinality change

   When running `./gradlew verifyChangePolicy`, use `-PchangeClass=<class>` to
   override auto-detection. The evaluator accepts:
   - `runtime-behaviour` (default) — rejects production+baseline and analyzer+runtime mixes
   - `build-logic` — allows analyzer changes, still rejects baseline+production mixes
   - `baseline-migration` — permits analyzer + baseline changes together

2. **List:**
   - intended files
   - protected invariants
   - expected measurement changes
   - required verification commands

3. **Run the relevant pre-change verification** to establish that the branch starts from a valid state.

### Stop and report before continuing when

- implementation unexpectedly requires changing an analyzer
- analyzer output identity, cardinality, scope, or schema would change
- `config/quality/0.6.0-baseline.json` would need modification
- a deviation ceiling would need to increase
- a CI workflow must be weakened or bypassed
- the requested task expands into another roadmap epic

### Forbidden (unless explicitly required by the task)

- regenerating the canonical baseline
- increasing deviation ceilings to make CI pass
- changing production code and its governing analyzer in the same PR
- changing a quality gate after it reports a production defect
- claiming completion based only on `./gradlew test`

### After editing

- run `./gradlew verifyPr` (primary local gate — runs subproject tests, build-logic tests, maintainability baseline, and change policy)
- for additional CI-equivalent steps, see `.github/AGENTS.md` for local equivalents
- in CI, set `-PchangePolicyBase=${{ github.event.pull_request.base.sha }}` for accurate PR-delta comparison
- inspect `git diff` for unintended files
- report every command run and its result
- report skipped checks explicitly
- do not push while a required local check is failing

### Merge authorization

An agent may push branches and open or update pull requests. An agent must **never** merge a pull
request that targets `master` or an `epic/**` branch: those merges require explicit authorization
from the repository owner, given after the review findings on the exact head have been read.

Epic branches carry no branch protection or required-review rule, and an agent acting through the
owner's credentials would satisfy any such rule that did exist, so this instruction — not the
platform — is the merge gate. Green CI is necessary and never sufficient: exact-head CI, the
Architecture Authority and the Sovereign Runtime RC checks can all pass while no approval exists.

## CI Failure Protocol

When a pipeline fails:

1. Read the exact failed step and its complete log.
2. Download and inspect associated artifacts.
3. Categorize the failure:
   - `production-defect` — bug in production code
   - `test-defect` — test is wrong or flaky
   - `analyzer-defect` — scanner/verifier has a bug
   - `baseline-mismatch` — current measurement differs from committed baseline
   - `environment-mismatch` — CI environment differs from local
   - `workflow-defect` — CI workflow configuration is wrong
   - `flaky-external` — network, rate limit, or transient dependency failure
4. State the diagnosed category and evidence before editing.
5. Before attributing a failing required check to the candidate change, reproduce the same failing test
   or suite at the exact pristine base when feasible. A failure may be classified as
   pre-existing/environmental only when the base reproduces the same relevant failure signature; file
   ownership or apparent unrelatedness alone is not evidence. Record both candidate and base
   commands/results in the completion report.
6. Do not modify a gate until evidence shows the gate is incorrect.
7. Do not modify a deviation until the current and canonical populations have been measured.
8. After two unsuccessful fixes, stop and provide a root-cause report rather than applying another speculative patch.

## Engineering Evidence Protocol

The CI Failure Protocol above says how to diagnose a failure. These rules say what counts as
evidence at all, and they bind every conclusion in a completion report, a PR description or an
answer to the owner. The procedures behind them live in the agent-side `engineering-evidence`
skill; this list is the short, authoritative form for this repository.

1. **No conclusion from truncated or incomplete output.** Capture a command's full, structured
   output and read that. A truncated tail, a scrollback, a summary line, or an artifact a previous
   run left behind is not the result of this run.
2. **No base-red claim without an equivalent base/head comparison.** Same task, same properties,
   at the pristine base and at the head; compare *diagnostic identities*, not just counts, and not
   the failing family alone. "It also fails at the base" needs both runs recorded — apparent
   unrelatedness or file ownership is not evidence.
3. **No certification from a noncanonical command.** Reproduce the invocation the CI job uses,
   including its `-P` properties. A gate run without its base-ref or base-policy property measures
   something else, and its verdict does not transfer. When local and CI disagree, the CI invocation
   decides, and the local result is reported as an invocation artifact.
4. **Critical behavioural tests must discriminate.** A test guarding an invariant must fail when
   that invariant is violated and pass when it holds; record both transcripts. A test whose fixture
   silently selects a different branch, class or default than the one it names proves nothing.
5. **Mutation verdicts need verified application and valid detection evidence.** Record the exact
   mutation identity, prove the patch applied to the tree it is claimed against, run a matched
   control, and state the verdict *and* its mechanism: assertion, crash, reproducible
   non-termination, survivor, invalid or undetermined. A timeout is not a kill, and a
   certification does not survive a head it was not run against.
6. **Public Kotlin/JVM changes need binary-compatibility checks.** Constructors (including the
   synthetic default-argument ones), `copy`/`copy$default`, `componentN`, getters and static
   members are part of the contract. Where compatibility matters, exercise an already-compiled
   consumer of the previous artifact against the new one, with a control run.
7. **Label every claim.** In a completion report each claim is VERIFIED (reproduced, command and
   output recorded), OBSERVED (seen once, or not at the canonical invocation), INFERRED (follows
   from verified facts) or UNRESOLVED (not established). Never present an unresolved failure as
   green, and state a withdrawn claim as withdrawn instead of dropping it.
8. **Repeated failure of a hypothesis triggers diagnosis, not more patching.** After two attempts,
   stop and produce a root-cause report. That is a diagnosis step, not permission to abandon the
   goal: say what is ruled out, what remains, and what the next discriminating experiment is.

## Completion Report Format

Finish every implementation task with a structured report:

```
## Scope
Files intentionally changed.

## Invariants
What was preserved or strengthened.

## Verification
- command — result
- command — result
- each claim labelled VERIFIED / OBSERVED / INFERRED / UNRESOLVED

## Quality impact
- cancellation findings: before → after
- nondeterminism findings: before → after
- API changes: none / listed
- deviations changed: none / listed

## Not run
Checks skipped and why.

## Remaining risks
Known limitations or follow-up work.
```

"No remaining blockers" is only allowed when all mandatory entries have concrete evidence.

## Repository Guidance

As the repository grows:

- keep `tramai-core` small and dependency-light
- keep `tramai-engine` focused on orchestration
- keep `tramai-structured` focused on structured-output mechanics
- keep optional modules truly optional
- keep framework adapters thin

## In Case of Ambiguity

When choosing between convenience and clarity, choose clarity.

When choosing between implicit behavior and explicit behavior, choose explicit behavior.

When choosing between lighter code and better-tested code, choose the code that is easier to trust.
