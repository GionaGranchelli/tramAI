# Change Guides

Step-by-step guides for the most common modifications in the TramAI repository. Each guide names the exact extension point (interface/SPI/builder), the files that must change, the mandatory contract tests (TCKs), and the verification commands.

**Start here:** [`ARCHITECTURE.md`](../../ARCHITECTURE.md) for the module map and ownership pointers; then open the guide for the change you are making; then follow [`AGENTS.md`](../../AGENTS.md) for the execution protocol (change classification, stop conditions, gates).

## Guides

| Change | Guide | Key contract |
|---|---|---|
| New provider adapter module | [adding-a-provider.md](adding-a-provider.md) | `ProviderTck` + `ProviderTckEnrollmentArchitectureTest` |
| New workflow step (built-in or external) | [adding-a-workflow-step.md](adding-a-workflow-step.md) | step TCK + digest golden + cancellation scanner |
| New persistence store implementation | [adding-a-store.md](adding-a-store.md) | shared store TCK + `*EnrollmentArchitectureTest` |
| New runtime event (catalogue + guards) | [adding-an-event.md](adding-an-event.md) | `RuntimeEventCatalogueArchitectureTest` (ASM + source) |
| New approval state / transition | [adding-an-approval-state.md](adding-an-approval-state.md) | `ApprovalStoreTck` + `ApprovalContinuationStoreTck` + lifecycle-model property tests |
| Change structured-output constraints | [changing-structured-output-constraints.md](changing-structured-output-constraints.md) | `StructuredOutputContractTck` + `ContractEvolutionTest` |

## How the guides stay honest

Every guide was written against the current source tree (verified file:line references). The TCKs named by each guide are **independent oracles** — they fail loudly on drift. If a guide's facts no longer match the code, the codebase moved: update the guide in the same PR that moves the contract, or the next agent following it will waste a review round.

## Related navigation

- Module cards with per-module responsibilities: [`docs/modules/`](../modules/README.md)
- Runtime execution ownership map: [`execution-sequence.md`](../execution-sequence.md)
- Machine-readable module catalog / boundaries: [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml), [`config/quality/module-boundaries.yml`](../../config/quality/module-boundaries.yml)
