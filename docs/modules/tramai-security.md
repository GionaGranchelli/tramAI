# Module: `tramai-security`

> **One-liner:** Policy enforcement, approval gates, audit and evidence for governed AI invocations.
> **Classification / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Owns governance semantics: policy decision points (`DefaultPolicyEngine`, `RuleBasedDlpInterceptor`), approval lifecycle coordination, audit trail and evidence records for governed AI invocations.

### Public entry points

- `DefaultPolicyEngine` — policy evaluation for governed invocations
- `RuleBasedDlpInterceptor` — DLP redaction for provider I/O
- `dev.tramai.security.audit.*` — `AuditEngine`, `AuditStore`, hash-chain validation
- `dev.tramai.security.approval.*` — approval gate coordination, in-memory store implementations (`InMemoryApprovalStore`, `InMemoryApprovalContinuationStore`)
- `dev.tramai.security.evidence.*` — evidence records

Verify the full public surface against `tramai-security/api/tramai-security.api`.

### Internal extension points

- Store SPIs (`ApprovalStore`, `ApprovalContinuationStore`, `AuditStore`) — implemented by `tramai-persistence-file` / `tramai-persistence-jdbc`

### Significant dependencies

- `api(tramai-core)`; `implementation(kotlinx-coroutines-core)`, `implementation(jackson-databind)` (see [module-catalog.yml](../../config/quality/module-catalog.yml))

### Lifecycle ownership

- No process/runtime resource lifecycle owned by this module. Stores borrow caller-supplied resources where applicable; ownership remains with the caller/composition layer. Approval/continuation state machines are behavioral contracts, not resource lifecycle.

### Thread-safety and concurrency

- Store implementations manage per-record locking; in-memory stores are synchronized. No blanket guarantee applies beyond each store's documentation.

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
