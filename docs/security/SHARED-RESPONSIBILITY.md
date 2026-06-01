# SHARED-RESPONSIBILITY.md — TramAI Enterprise

This document defines what TramAI enforces and what the deploying organization must provide. It is the contract between the runtime and the infrastructure.

---

## Principle

> TramAI provides application-level controls. The organization provides infrastructure-level and procedural controls. Strong sovereignty requires both.

---

## Responsibility Matrix

### Data Protection

| Concern | TramAI Responsibility | Organization Responsibility |
|---------|----------------------|----------------------------|
| Data classification enforcement | Enforces routing by classification (PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED) | Defines classification rules and labels |
| Data at rest encryption | Not in scope | Encrypt storage volumes, databases, backups |
| Data in transit encryption | Enforces TLS for managed HTTP connections | Configure certificates, manage trust stores |
| PII redaction in model output | Provides DLP interceptor SPI | Configure redaction rules, validate coverage |
| Context minimization | Provides context trimming hooks | Define what data is sent to each operation |

### Model Governance

| Concern | TramAI Responsibility | Organization Responsibility |
|---------|----------------------|----------------------------|
| Model allowlist | Enforces registry — only approved models load | Maintain model registry, approve new models |
| Model checksum verification | Verifies checksums for TramAI-managed artifacts (GGUF, OCI, pre-staged) | Provide correct checksums, secure artifact storage |
| Remote model endpoint verification | Validates endpoint identity, TLS, declared metadata | Contractual controls, endpoint monitoring, audit |
| Model lifecycle (update, deprecate, retire) | Provides versioned registry | Define lifecycle policies, test new versions |

### Tool and Action Control

| Concern | TramAI Responsibility | Organization Responsibility |
|---------|----------------------|----------------------------|
| Tool authorization | Deny-by-default, permission check, risk classification | Define tool permissions, assign risk levels |
| Human approval gates | Suspends workflow, enforces approval requirement | Provide approval mechanism, define approvers |
| Approval timeout | Auto-deny on timeout | Configure timeout values per risk level |
| Tool sandboxing | Provides sandboxing guidance (filesystem, network) | Implement sandboxing at infrastructure level |

### Network Egress

| Concern | TramAI Responsibility | Organization Responsibility |
|---------|----------------------|----------------------------|
| Application-level egress | Deny-by-default for managed destinations (providers, HTTP tools) | Configure allowlist, audit allowed destinations |
| Infrastructure-level egress | Not enforceable from JVM | Firewall rules, Kubernetes NetworkPolicy, mandatory proxy, container policies |
| Native/subprocess egress | Not enforceable from JVM | Sandboxing, seccomp, AppArmor, SELinux |
| DNS leakage prevention | Verifies no DNS calls from managed code | DNS policy, split-horizon DNS, firewall rules |
| Zero-egress verification | Automated zero-egress test suite | Run tests in target environment, validate results |

### Audit and Evidence

| Concern | TramAI Responsibility | Organization Responsibility |
|---------|----------------------|----------------------------|
| Audit event emission | Synchronous emission for every policy decision | Configure retention, export, archival |
| Audit integrity | Hash chain, append-only storage | Provide WORM storage or external integrity sink |
| Audit storage availability | Configured fail mode (FAIL_CLOSED / FAIL_SAFE_READ_ONLY) | Provision sufficient storage, monitor capacity |
| Evidence export | Exportable evidence packages | Define export schedule, validate completeness |
| Incident replay | Reconstruct workflow from audit events | Investigate incidents, define response procedures |

### Supply Chain

| Concern | TramAI Responsibility | Organization Responsibility |
|---------|----------------------|----------------------------|
| SBOM generation | CycloneDX SBOM per release | Review SBOM, assess vulnerabilities |
| Dependency scanning | Automated scanning in CI | Remediate or accept known vulnerabilities |
| Build provenance | SLSA Build L1 (v0.5), Build L2 (v1.0) | Verify provenance before deployment |
| Artifact signing | Signed releases and OCI images | Verify signatures before deployment |
| Offline update procedure | Documented runbook | Test runbook, maintain artifact mirror |

### Deployment Profiles

| Profile | TramAI Provides | Organization Provides |
|---------|----------------|----------------------|
| SOVEREIGN_CONNECTED | Application-level egress policy, provider routing, model registry | Controlled infrastructure, limited egress |
| OFFLINE_RUNTIME | OCI bundle, verification manifest, model artifact bundle | No internet during execution, pre-staged artifacts |
| ISOLATED_NETWORK | Same as OFFLINE_RUNTIME + zero-egress tests | Network-level egress blocked (firewall, NetworkPolicy) |
| AIR_GAPPED | Same as ISOLATED_NETWORK + transfer runbook | Controlled transfer procedures, no operational external network link |

---

## What TramAI Cannot Enforce

These are explicitly outside TramAI's scope. The organization must handle them:

1. **Native code egress** — A compromised C library, GPU driver, or model runtime can open sockets. TramAI runs in the JVM and cannot intercept native syscalls.
2. **Subprocess egress** — A spawned subprocess can make its own network calls. TramAI provides allowlist guidance but cannot enforce at OS level.
3. **Physical access** — Physical access to hardware bypasses all software controls.
4. **Kernel-level compromise** — Root access or kernel exploit bypasses JVM-level controls.
5. **Side-channel attacks** — Power analysis, timing attacks, electromagnetic emissions are outside software scope.
6. **Insider threat with legitimate access** — An authorized operator with policy configuration access can weaken controls. Mitigated by audit trail and separation of duties.
7. **Model weight verification for remote endpoints** — TramAI cannot cryptographically prove which weights are loaded behind a remote Ollama/vLLM endpoint. Relies on endpoint identity, TLS, and organizational controls.

---

*Adopted June 2026. Updated when control boundaries change.*
