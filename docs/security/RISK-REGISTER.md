# RISK-REGISTER.md — TramAI Enterprise

Phase 0 initial risk register. Probability and impact are rated 1 (low) to 5 (critical). Residual risk is assessed after planned controls are applied.

---

| ID | Risk | Probability | Impact | Inherent | Controls | Control Status | Current Risk | Target Residual | Owner |
|----|------|------------|--------|----------|-----------|----------------|--------------|-----------------|-------|
| R-01 | Prompt injection → data exfiltration | 4 | 5 | Critical | Context minimization, data classification routing, output DLP, field-level policy, structured output validation | Planned | Critical | Medium | Engineering |
| R-02 | Prompt injection → unauthorized tool execution | 4 | 5 | Critical | Tool deny-by-default, permission check, risk classification, approval gates | Planned | Critical | Low | Engineering |
| R-03 | RESTRICTED data routed to unauthorized provider | 3 | 5 | Critical | Data classification enforcement, provider routing policy, no silent fallback | Planned | Critical | Low | Engineering |
| R-04 | High-risk action executed without human approval | 3 | 5 | Critical | Approval gates, risk classification, workflow suspension, timeout auto-deny | Planned | Critical | Low | Engineering |
| R-05 | Workflow definition tampered | 2 | 4 | High | Workflow versioning, digest, audit on change | Planned | High | Low | Engineering |
| R-06 | Model artifact substitution | 2 | 4 | High | Model registry checksum, startup verification | Planned | High | Low | Engineering |
| R-07 | Remote model endpoint unverifiable | 3 | 3 | Medium | Endpoint identity, TLS, declared metadata, contractual controls | Planned | Medium | Medium | Engineering |
| R-08 | Audit trail tampered or deleted | 2 | 4 | High | Append-only storage, hash chain, WORM/sink export, fail-closed on storage failure | Planned | High | Low | Engineering |
| R-09 | Audit storage DoS → workflow blocked | 3 | 3 | Medium | Configured fail modes (FAIL_CLOSED vs FAIL_SAFE_READ_ONLY), durable local buffer with limit | Planned | Medium | Medium | Engineering |
| R-10 | Supply-chain compromise (malicious dependency) | 3 | 5 | Critical | CycloneDX SBOM, dependency scanning, provenance, artifact signing | Planned | Critical | Medium | Engineering |
| R-11 | Compromised build infrastructure | 2 | 5 | High | SLSA Build L1 (v0.5) → L2 (v1.0), signed artifacts, reproducible builds | Planned | High | Medium | Engineering |
| R-12 | Native/subprocess egress bypass | 3 | 3 | Medium | Application-level egress policy + infrastructure-level isolation (firewall, NetworkPolicy) | Planned | Medium | Medium | Shared |
| R-13 | MCP token passthrough | 3 | 3 | Medium | OAuth audience validation (HTTP), subprocess allowlist (stdio), scoped permissions | Planned | Medium | Low | Engineering |
| R-14 | MCP plugin bypasses policy | 2 | 4 | High | Plugin allowlist, sandbox guidance, policy enforced at MCP boundary | Planned | High | Low | Engineering |
| R-15 | Policy misconfiguration (overly permissive) | 3 | 4 | High | Deny-by-default, policy audit, configuration validation at startup | Planned | High | Medium | Operator |
| R-16 | Zero-day in model runtime (Ollama/vLLM) | 2 | 4 | High | Model registry, checksum (managed artifacts), endpoint identity (remote) | Planned | High | Medium | Engineering |
| R-17 | Novel prompt injection technique | 3 | 4 | High | Layered controls, OWASP alignment, continuous security testing | Planned | High | Medium | Engineering |
| R-18 | Offline claim falsification (hidden cloud calls) | 2 | 4 | High | Automated zero-external-egress tests, release pipeline validation | Planned | High | Low | Engineering |
| R-19 | Physical access to air-gapped hardware | 1 | 5 | Medium | Operational runbook, physical security (infrastructure responsibility) | Planned | Medium | Low | Operator |
| R-20 | Design partner disengagement | 3 | 3 | Medium | Early engagement, iterative validation, no premature enterprise promises | Planned | Medium | Medium | Product |

---

## Risk Taxonomy

| Level | Criteria |
|-------|----------|
| **Critical** | Business-ending event: data breach, regulatory penalty, irreversible damage |
| **High** | Significant impact: audit failure, customer loss, operational disruption |
| **Medium** | Manageable impact: limited scope, recoverable, no regulatory exposure |
| **Low** | Minor impact: contained, quickly recoverable, no external visibility |

---

## Control Status Values

| Status | Definition |
|--------|------------|
| **Planned** | Control designed but not yet implemented |
| **In progress** | Implementation underway, not yet tested |
| **Tested** | Control implemented and passing negative tests |
| **Externally validated** | Independent verification (penetration test, audit) |

## Control Effectiveness Assumptions

Controls are marked as Target Residual Low only when:

1. The control is implemented and tested (not planned)
2. Negative tests prove the control blocks the threat
3. The control cannot be bypassed through any documented code path

Controls currently marked as Medium residual risk require additional validation (external penetration test, infrastructure hardening, or procedural maturity) before they can be downgraded.

---

*Adopted June 2026. Reviewed quarterly or after any significant architectural change.*
