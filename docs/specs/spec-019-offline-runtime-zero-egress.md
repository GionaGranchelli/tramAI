# SPEC-019: Offline Runtime Profile and Zero-Egress Verification Harness

**Status:** implemented (PR #31)
**PR:** #31
**Branch:** feat/offline-runtime-zero-egress

## Problem Statement

SPEC-017 (approved-model registry) and SPEC-018 (local-model artifact verification) together prove that configured provider-model identity and local artifact bytes are cryptographically verified before sovereign runtime use. However, neither spec addresses the following question:

> *"Can the sovereign TramAI runtime be composed with only local providers and execute a reference workflow successfully inside a container where external network access is provably blocked?"*

A deployment advertised as "offline capable" must not silently make cloud calls at runtime. This spec provides an explicit `OFFLINE` deployment mode with build-time provider-zone validation and a Docker-based zero-egress verification harness that proves:
- loopback networking is available inside a `--network=none` container;
- external TCP and DNS are blocked by infrastructure controls;
- the sovereign runtime builds, verifies local artifacts, and completes a reference loopback invocation through the real service proxy and engine.

## Definitions

| Term | Definition |
|------|------------|
| **Sovereign deployment** | The application runs on infrastructure the organization controls. Models, data, and policy enforcement stay within the organization's boundary. |
| **Offline deployment** | The application runs without internet connectivity at runtime. All dependencies, models, and configurations are pre-staged. Verified by automated zero-egress tests at the application level. |
| **Isolated-network deployment** | Offline deployment with network-level egress blocked by infrastructure controls (firewall, Kubernetes NetworkPolicy, proxy enforcement). |
| **Air-gapped deployment** | Isolated-network deployment with controlled, audited transfer procedures and no operational network link to external networks. |

## Scope

Implement:

1. `SovereignDeploymentMode` enum (`STANDARD`, `OFFLINE`)
2. `deploymentMode` field on `SovereignProfileConfiguration` (default: `STANDARD`)
3. Build-time offline route validation in `SovereignTramai.Builder`
4. Runnable example module: `examples/sovereign-offline-verification`
5. Loopback HTTP server using JDK `com.sun.net.httpserver.HttpServer`
6. Loopback `ModelProvider` using JDK `HttpClient`
7. PR #30 artifact verification integration
8. `ZeroEgressVerificationReportV1` with deterministic JSON output
9. Dockerfile + `scripts/verify-zero-egress.sh` harness
10. CI job for zero-egress verification
11. Unit and integration tests

## Non-Goals

This PR does NOT implement:

- Periodic artifact re-attestation or filesystem watchers
- Runtime model-server process management
- JVM-global socket monkey-patching or Java Security Manager
- Automatic enforcement of `ModelArtifactVerificationSettings` for OFFLINE mode
- Distributed leases, key rotation, JDBC stores, or background workers
- Replacing infrastructure-level network isolation (firewalls, NetworkPolicy, mandatory proxies, sandboxing)
- Proving that an external inference server loaded verified bytes

## SovereignDeploymentMode

```kotlin
package dev.tramai.sovereign

enum class SovereignDeploymentMode {
    STANDARD,
    OFFLINE,
}
```

`STANDARD` is the default — existing sovereign routing behavior. All provider trust zones are permitted according to policy.

`OFFLINE` requires that every registered provider, primary route, fallback route, and default provider targets `ProviderTrustZone.LOCAL`.

## SovereignProfileConfiguration Extension

Add `val deploymentMode: SovereignDeploymentMode = SovereignDeploymentMode.STANDARD` to the existing `data class`. Backward compatible — existing consumers remain `STANDARD`.

## Offline Build-Time Invariants

`SovereignTramai.Builder.build()` invokes `validateOfflineDeployment(profile)` after standard provider/route validation but before artifact verification and registry lookup:

1. If `deploymentMode != OFFLINE`, return immediately
2. For each registered provider: `require(zone == LOCAL)` — `offline-profile-non-local-provider-rejected`
3. For each primary route: `require(zone == LOCAL)` — `offline-profile-non-local-primary-route-rejected`
4. For each fallback route: `require(zone == LOCAL)` — `offline-profile-non-local-fallback-rejected`
5. For the default provider: `require(zone == LOCAL)` — `offline-profile-non-local-default-provider-rejected`

Use fixed safe reason codes only. No raw paths, prompts, or secrets in exception messages.

## Docker --network=none Verification Strategy

The zero-egress harness runs the example module inside a Docker container started with `--network=none`. Inside the container:

- **Loopback networking** works (127.0.0.1 is available in any container)
- **External TCP** to a numeric IP (1.1.1.1:443) fails
- **DNS resolution** of `example.com` fails

The example module:
1. Starts a JDK `HttpServer` on `127.0.0.1:0`
2. Registers a `LoopbackHttpModelProvider` targetting the loopback URL
3. Builds the sovereign runtime with `deploymentMode = OFFLINE`
4. Creates the real TramAI service proxy
5. Invokes `OfflineEchoService.echo("offline-verification")`
6. Runs TCP and DNS probes
7. Validates the audit chain
8. Writes a deterministic JSON report

## Report Schema

```json
{
  "schemaVersion": 1,
  "deploymentMode": "OFFLINE",
  "runtimeBuildSucceeded": true,
  "loopbackProviderInvocationSucceeded": true,
  "loopbackProviderInvocationCount": 1,
  "externalTcpProbeBlocked": true,
  "externalDnsProbeBlocked": true,
  "configuredProviderZones": {
    "loopback-local-provider": "LOCAL"
  },
  "artifactVerificationReceiptCount": 1,
  "auditChainValid": true
}
```

Deterministic key ordering. No prompts, raw requests, filesystem paths, temporary directory names, IP addresses, approval tokens, secrets, environment variables, or stack traces.

## Residual Risks

| Risk | Mitigation |
|------|------------|
| TOCTOU: model file replaced after PR #30 verification | Accepted — deferred periodic re-attestation |
| Offline mode does not enforce artifact verification | Documented — `OFFLINE` is a composition contract, not artifact attestation |
| Native code or subprocess opens egress from inside the container | Infrastructure control (`--network=none`) blocks it; library-level socket interception is not attempted |
| Loopback server starts on 127.0.0.1 but a different process binds to the same port | Ephemeral port allocation (`0`) and jepsen-style retry mitigate this in the harness |
| DNS probe may fall through to system resolver in some JDK versions | TCP probe is the primary check; DNS probe is supplementary |

## Roadmap After PR #31

| PR | Scope |
|----|-------|
| PR #29 | ✅ Encrypted suspended invocation store and restart-safe recovery |
| PR #30 | ✅ Local-model artifact manifest and byte-level verification |
| PR #31 | ✅ Offline runtime profile and zero-egress verification harness |
| PR #32 | Evidence pack, SBOM, and deployment provenance |
