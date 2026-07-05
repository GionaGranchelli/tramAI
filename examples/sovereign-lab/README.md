# TramAI Sovereign Lab — Physical Local-Model Testing

This profile lets you run TramAI Sovereign Runtime **locally** on your machine with:

- PostgreSQL for durable persistence
- A local model endpoint (OpenAI-compatible: Ollama, llama.cpp, vLLM, LM Studio, LocalAI)
- Approval workflow fully wired
- REST control plane + reviewer UI enabled
- **Zero egress** — no cloud providers called

The lab uses Docker Compose for PostgreSQL. The local model runtime is not containerized by default; bring your own OpenAI-compatible local endpoint such as Ollama, llama.cpp, vLLM, LM Studio, or LocalAI. No CI, no cloud — just your machine.

For an overview of the complete evidence chain — from bundle creation through finalization, verification, release-readiness, reviewer handoff, and archive export — see [EVIDENCE-CHAIN.md](./EVIDENCE-CHAIN.md).

---

## CI Smoke Verification

Run these commands to verify the lab profile wiring without a real model or Docker:

```bash
# Verify lab files and configuration shape
./gradlew verifySovereignLabProfile

# Verify the sovereign-lab profile boots with JDBC persistence and local provider config
./gradlew verifySovereignLabRuntimeSmoke
```

`verifySovereignLabProfile` checks that the profile YAML, README, and Docker Compose exist and contain the expected concepts. `verifySovereignLabRuntimeSmoke` starts the Spring application with the `sovereign-lab` profile against embedded PostgreSQL and verifies the local-only configuration can boot without a cloud provider. No real LLM call is made — the local provider URL points to an unused localhost endpoint that is never called by this smoke test. The local OpenAI-compatible provider is created from the `tramai.providers.local-lab-provider` YAML configuration by `OpenAiCompatibleProviderAutoConfiguration`. The datasource is still registered directly via `ApplicationContextInitializer`; a future PR may close this gap with datasource auto-configuration.

---

## Optional Local Model Invocation Proof

This test is **not** part of normal CI because it requires a real local OpenAI-compatible model endpoint. Uses embedded PostgreSQL (no Docker) and a temporary encryption key.

**Prerequisites:**

```bash
ollama serve
ollama pull qwen2.5:7b
```

**Run:**

```bash
export TRAMAI_ENABLE_LOCAL_MODEL_TEST=true
export TRAMAI_LOCAL_BASE_URL=http://localhost:11434/v1
export TRAMAI_LOCAL_API_KEY=local-dev
export TRAMAI_LOCAL_MODEL=qwen2.5:7b

./gradlew verifySovereignLabLocalModel
```

Or use the helper script (reads the same environment variables):

```bash
examples/sovereign-lab/local-model-smoke.sh
```

The test verifies that the sovereign-lab profile can invoke the configured `local-lab-provider` against a real local model endpoint.

For optional latency diagnostics, run `examples/sovereign-lab/local-model-benchmark.sh`. The benchmark is operator-triggered and not part of CI.

For reproducible demo, grant, or enterprise evidence capture, follow [EVIDENCE.md](./EVIDENCE.md).

To create a timestamped evidence bundle with all templates and a manifest:

```bash
examples/sovereign-lab/create-evidence-bundle.sh
```

See [EVIDENCE.md](./EVIDENCE.md#create-an-evidence-bundle) for details.

To verify the bundle scaffold layout:

```bash
./gradlew verifySovereignLabEvidenceBundle
```

Each generated bundle includes a machine-readable **manifest.json** describing the bundle type, source commit, required files, and claim boundaries. The manifest also includes SHA-256 digests and file sizes so reviewers can detect whether generated evidence files changed after bundle creation.

To verify an existing generated bundle:

```bash
examples/sovereign-lab/verify-evidence-bundle.sh \
  examples/sovereign-lab/build/evidence-bundles/<timestamp>
```

### Evidence bundle lifecycle

```bash
# 1. Create
examples/sovereign-lab/create-evidence-bundle.sh

# 2. Fill generated evidence files (manual)

# 3. Finalize — refresh manifest digests after filling
examples/sovereign-lab/finalize-evidence-bundle.sh \
  examples/sovereign-lab/build/evidence-bundles/<timestamp>

# 4. Verify the finalized bundle
examples/sovereign-lab/verify-evidence-bundle.sh \
  examples/sovereign-lab/build/evidence-bundles/<timestamp>
```

---

## Prerequisites

- Java 21+
- Docker (for PostgreSQL)
- A local OpenAI-compatible model endpoint (e.g. Ollama, llama.cpp server)

---

## Quick Start

### 1. Start PostgreSQL

```bash
docker compose -f examples/sovereign-lab/docker-compose.yml up -d
```

This starts PostgreSQL 16 on port `5432` with database `tramai_sovereign_lab`.

### 2. Start your local model server

**Ollama example:**

```bash
ollama pull qwen2.5:7b
ollama serve
```

The lab profile expects an OpenAI-compatible endpoint at `http://localhost:11434/v1` by default.

**Other options:**

| Server | Default URL | Environment variable |
|--------|-------------|---------------------|
| Ollama | `http://localhost:11434/v1` | `TRAMAI_LOCAL_BASE_URL` |
| llama.cpp server | `http://localhost:8080/v1` | `TRAMAI_LOCAL_BASE_URL` |
| vLLM | `http://localhost:8000/v1` | `TRAMAI_LOCAL_BASE_URL` |
| LM Studio | `http://localhost:1234/v1` | `TRAMAI_LOCAL_BASE_URL` |
| LocalAI | `http://localhost:8080/v1` | `TRAMAI_LOCAL_BASE_URL` |

### 3. Generate an encryption key

```bash
mkdir -p .local
openssl rand -base64 32 > .local/sovereign.key
```

### 4. Start TramAI with the sovereign-lab profile

```bash
./gradlew :examples:spring-sovereign-starter:bootRun \
  --args='--spring.profiles.active=sovereign-lab'
```

### 5. Submit a high-risk claim

```bash
curl -X POST http://localhost:8080/tramai/examples/regulated-claim-triage \
  -H 'Content-Type: application/json' \
  -d '{"claimId":"claim-1","riskLevel":"HIGH","amount":5000}'
```

Expected response: `{"status":"SuspendedForApproval",...}`

### 6. View the approval inbox

```bash
curl http://localhost:8080/tramai/sovereign/approvals
```

Expected: one approval work item appears.

### 7. Approve the claim

```bash
# Get the approval ID from step 6, then:
curl -X POST http://localhost:8080/tramai/sovereign/approvals/{id}/approve \
  -H 'Content-Type: application/json' \
  -d '{"decisionBy":"lab-reviewer","reason":"approved in lab"}'
```

### 8. Confirm durability

Stop and restart the app:

```bash
# Ctrl+C the app, then:
./gradlew :examples:spring-sovereign-starter:bootRun \
  --args='--spring.profiles.active=sovereign-lab'
```

Then check the inbox again — the approval record survives.

---

## Configuration Reference

| Environment variable | Default | Description |
|---------------------|---------|-------------|
| `TRAMAI_LOCAL_BASE_URL` | `http://localhost:11434/v1` | OpenAI-compatible base URL |
| `TRAMAI_LOCAL_MODEL` | `qwen2.5:7b` | Model name on the local endpoint |
| `TRAMAI_LOCAL_API_KEY` | `local-dev` | API key (most local servers ignore it) |
| `TRAMAI_SOVEREIGN_KEY_FILE` | `./.local/sovereign.key` | Encryption key for persisted data |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/tramai_sovereign_lab` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `tramai` | PostgreSQL user |
| `SPRING_DATASOURCE_PASSWORD` | `tramai` | PostgreSQL password |

---

## What This Proves

- TramAI Sovereign Runtime runs with a **real local model** (not a deterministic test stub)
- Approval workflow creates durable persisted records
- Data survives application restart
- No cloud providers are called — zero egress
- REST control plane and reviewer UI work

---

## Release Readiness

Before treating a sovereign lab run as release-candidate evidence, follow:

[RELEASE-READINESS.md](./RELEASE-READINESS.md)

The checklist defines required Gradle gates, required evidence files, the bundle lifecycle, allowed claims, forbidden claims, and release-candidate blockers.

## Reviewer Guide

For instructions on verifying and interpreting a finalized evidence bundle, see:

[REVIEWER-GUIDE.md](./REVIEWER-GUIDE.md)

The reviewer guide explains how to run the verifier, inspect `manifest.json`, review claim-boundary flags, evaluate evidence files, and distinguish structural tamper-evidence from evidence truth, compliance, certification, or production-readiness claims.

## Archive Export

After finalizing and verifying a bundle, create a portable archive:

```bash
examples/sovereign-lab/package-evidence-bundle.sh \
  examples/sovereign-lab/build/evidence-bundles/<timestamp>
```

This creates:

```
examples/sovereign-lab/build/evidence-archives/<timestamp>.tar.gz
examples/sovereign-lab/build/evidence-archives/<timestamp>.tar.gz.sha256
```

Archive export verifies the bundle before packaging. It does not sign, certify, upload, or validate evidence truth. For the signing boundary, see [ARCHIVE-SIGNING.md](./ARCHIVE-SIGNING.md).

Validate the archive checksum:

```bash
cd examples/sovereign-lab/build/evidence-archives
sha256sum -c <timestamp>.tar.gz.sha256
```

For example:

```bash
cd examples/sovereign-lab/build/evidence-archives
sha256sum -c test-bundle.tar.gz.sha256
```

Archive export uses GNU tar options for deterministic CI output. On non-GNU tar systems, use the verified bundle directory directly or run the packaging step in a Linux environment.

The Gradle evidence-bundle verification also checks that packaging the same finalized bundle twice produces the same archive SHA-256.

### Archive Verifier

To verify an archived evidence bundle without manually extracting it into the current directory:

```bash
examples/sovereign-lab/verify-evidence-archive.sh \
  examples/sovereign-lab/build/evidence-archives/<timestamp>.tar.gz
```

The archive verifier checks the SHA-256 sidecar, rejects unsafe archive entries (absolute paths, traversal paths, symlinks, hardlinks, special files), extracts into a temporary directory, and runs `verify-evidence-bundle.sh` on the extracted bundle.

The `.sha256` sidecar must contain exactly one line with a SHA-256 digest and the archive filename. Both `sha256sum` text mode and binary mode (`*filename`) markers are accepted.

This is the recommended way for reviewers to inspect an archived evidence bundle. The archive verifier is covered by Gradle negative fixtures for malformed sidecars and unsafe tar entries.

Archived evidence bundles must contain one top-level bundle directory.

### Optional Signature Verification

If the archive checksum sidecar has a detached signature, a reviewer can verify it:

```bash
examples/sovereign-lab/verify-evidence-archive-signature.sh \
  examples/sovereign-lab/build/evidence-archives/<timestamp>.tar.gz \
  reviewer-public-key.pem
```

The signature verifier checks `<archive>.tar.gz.sha256.sig` against `<archive>.tar.gz.sha256` using the caller-supplied public key, then runs the existing archive verifier.

This verifies the checksum sidecar's cryptographic origin. It does not prove evidence truth, operator identity, legal compliance, regulatory certification, or production readiness. The signer's public key is supplied by the reviewer and is not stored in this repository.

See [ARCHIVE-SIGNING.md](./ARCHIVE-SIGNING.md) for the full signing boundary.

---

## Files

| File | Purpose |
|------|---------|
| `docker-compose.yml` | PostgreSQL for local persistence |
| `README.md` | This file |
| `application-sovereign-lab.yml` | Spring Boot profile (in the example module) |
