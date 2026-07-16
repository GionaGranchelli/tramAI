# Tool Governance Example

Demonstrates governed tool permission decisions in TramAI: ALLOW, DENY, and REQUIRE_APPROVAL, with dedicated `tool.permission` runtime evidence.

## Prerequisites

- Java 21+
- No external models, Docker, databases, or credentials required

## Run

```bash
./gradlew :examples:tool-governance:run
```

## Scenarios

| Scenario | Tool | Decision | Behaviour |
|----------|------|----------|-----------|
| Customer lookup | `customer_lookup` | ALLOW | Tool executes, result reinjected, full loop completes |
| Account deletion | `account_delete` | DENY | Exposure allowed, execution denied, tool never runs |
| Payment | `payment` | REQUIRE_APPROVAL | Execution suspended, tool never runs, continuation created |

## Key Takeaways

- **Exposure permission is not execution permission.** A tool may be exposed to the model but denied at execution.
- **Denied tools never execute.** The tool call count remains zero after a DENY decision.
- **Approval-required tools never execute.** The workflow suspends before execution.
- **Tool events are partitioned** into `tool.permission` evidence, separate from generic `policy.decision`.
- **Raw tool arguments and secrets** never appear in audit or evidence output.

## Evidence

Each scenario exports its tool permission decisions through `ToolPermissionRuntimeEvidenceExporter` and prints a compact evidence summary. The example also proves that tool events are excluded from the generic `PolicyDecisionRuntimeEvidenceExporter` output.

## Related

- [Governed Tool Use Guide](../../docs/guides/governed-tool-use.md)
- [Tool Permission Model](../../docs/security/tool-permission-model.md)
- [Runtime Evidence Export Model](../../docs/evidence/runtime-evidence-export-model.md)
- [Approval Resume Example](../approval-resume/)
