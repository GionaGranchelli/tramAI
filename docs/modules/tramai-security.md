# Module: `tramai-security`

> **One-liner:** Policy enforcement, approval gates, audit and evidence for governed AI invocations.
> **Classification:** governance-security · published · preview API — see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Owns governance semantics: policy decision points (`DefaultPolicyEngine`, `RuleBasedDlpInterceptor`), approval lifecycle coordination, audit trail and evidence records for governed AI invocations.

### Public entry points

- `DefaultPolicyEngine` — policy evaluation for governed invocations
- `RuleBasedDlpInterceptor` — DLP redaction for provider I/O
- `dev.tramai.security.approval.*` — approval gate coordination, in-memory store implementations (`InMemoryApprovalStore`, `InMemoryApprovalContinuationStore`)
- `dev.tramai.security.audit.*` — `AuditEngine`, `AuditStore`, hash-chain validation
- `dev.tramai.security.evidence.*` — evidence records

### Internal extension points

- Store SPIs (`ApprovalStore`, `ApprovalContinuationStore`, `AuditStore`) — implemented by `tramai-persistence-file` / `tramai-persistence-jdbc`

### Significant dependencies

- `api(tramai-core)` only — governance sits above the engine contract and does not depend on runtime internals

### Lifecycle ownership

- Approval and continuation state machines; recovery coordination (`ApprovalRecoveryCoordinator`)

### Thread-safety and concurrency

- Store implementations manage per-record locking; in-memory stores are synchronized. Do not invent guarantees beyond what the store's own documentation states.

### Failure semantics

- Policy violations fail loudly with typed decisions; audit failures surface as evidence gaps rather than silent skips

### Contract tests / TCKs

- `InMemoryApprovalStoreTckTest`, `InMemoryApprovalContinuationStoreTckTest` — enrolled in `ApprovalStoreTck` / `ApprovalContinuationStoreTck` (tramai-testing)
- `DefaultPolicyEngineTest`, `RuleBasedDlpInterceptorTest`

### Do not

- Do not add Spring/framework dependencies here
- Do not bypass the policy decision points from engine code

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — governance-security layer
- `docs/adr/` — governance and approval decisions
