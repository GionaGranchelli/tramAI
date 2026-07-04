# Sovereign Lab Evidence Capture Guide

## Purpose

This guide explains how to capture reproducible evidence that TramAI Sovereign Runtime runs locally with:
- A local OpenAI-compatible model endpoint
- JDBC persistence
- Approval workflow
- No cloud provider (zero egress)

Use this for grant evidence, enterprise demos, release candidate evidence packs, or developer
reproducibility.

---

## Create an Evidence Bundle

Create a timestamped local evidence bundle:

```bash
examples/sovereign-lab/create-evidence-bundle.sh
```

This creates:

```
examples/sovereign-lab/build/evidence-bundles/<timestamp>/
```

Use the generated files to record command results, attach benchmark reports, and document the local environment.

To attach the optional benchmark report after running:

```bash
cp examples/spring-sovereign-starter/build/reports/sovereign-lab/local-model-benchmark/benchmark.json \
  examples/sovereign-lab/build/evidence-bundles/<timestamp>/reports/benchmark.json
```

The bundle is a local evidence scaffold. It does not define production performance thresholds, cloud comparisons, or regulatory certification.

The generated bundle also includes **manifest.json** — machine-readable metadata for reviewers and automation. It records:

- manifest schema version
- bundle type
- generation timestamp
- repository commit and branch
- required evidence files
- claim-boundary flags

The manifest does not certify the evidence, run local models, run benchmarks, or validate production readiness.

---

## Verify Evidence Bundle Scaffold

To verify that the evidence bundle helper still creates the expected local structure:

```bash
./gradlew verifySovereignLabEvidenceBundle
```

This verification only checks the scaffold layout and claim-boundary text. It does not run a local model, benchmark, database workflow, or production certification check.

---

## Evidence Checklist

- [ ] Environment details captured
- [ ] Local model endpoint running
- [ ] Sovereign lab profile booted
- [ ] Provider config shows `local-lab-provider`
- [ ] Opt-in local model invocation proof passed
- [ ] High-risk claim suspended for approval
- [ ] Approval inbox contains work item
- [ ] Approval/denial decision persisted
- [ ] App restart preserves state
- [ ] No cloud provider configured or selected

---

## 1. Environment Capture

```bash
java -version
./gradlew --version

# Local model endpoint
ollama list
curl -s http://localhost:11434/v1/models
```

Capture the output. Expected: Java 21+, Gradle 8.x, `qwen2.5:7b` (or equivalent) listed.

---

## 2. Local Model Invocation Proof

```bash
export TRAMAI_ENABLE_LOCAL_MODEL_TEST=true
export TRAMAI_LOCAL_BASE_URL=http://localhost:11434/v1
export TRAMAI_LOCAL_API_KEY=local-dev
export TRAMAI_LOCAL_MODEL=qwen2.5:7b

./gradlew verifySovereignLabLocalModel
```

**Expected output:**

```
BUILD SUCCESSFUL
SovereignLabLocalModelInvocationTest > invokes local OpenAI-compatible model through sovereign lab provider PASSED
```

---

## 3. Provider and Model Proof

The `application-sovereign-lab.yml` profile configures:

| Setting | Value | Environment variable |
|---------|-------|---------------------|
| `tramai.providers.local-lab-provider.type` | `openai` | — |
| `tramai.providers.local-lab-provider.base-url` | `http://localhost:11434/v1` | `TRAMAI_LOCAL_BASE_URL` |
| `tramai.providers.local-lab-provider.api-key` | `local-dev` | `TRAMAI_LOCAL_API_KEY` |
| `tramai.providers.local-lab-provider.model` | `qwen2.5:7b` | `TRAMAI_LOCAL_MODEL` |

Verify with the CI smoke test:

```bash
./gradlew verifySovereignLabRuntimeSmoke
```

**Expected output:**

```
verifySovereignLabRuntimeSmoke: sovereign lab runtime smoke tests passed.
```

This proves the provider bean is auto-configured from YAML (`OpenAiCompatibleProviderAutoConfiguration`).

---

## 4. Approval Suspension Proof

Start the lab:

```bash
docker compose -f examples/sovereign-lab/docker-compose.yml up -d
mkdir -p .local
openssl rand -base64 32 > .local/sovereign.key

./gradlew :examples:spring-sovereign-starter:bootRun \
  --args='--spring.profiles.active=sovereign-lab'
```

Submit a high-risk claim:

```bash
curl -X POST http://localhost:8080/tramai/examples/regulated-claim-triage \
  -H 'Content-Type: application/json' \
  -d '{"claimId":"evidence-claim-1","riskLevel":"HIGH","amount":5000}'
```

**Expected `SuspendedForApproval`:**

```json
{
  "status": "SuspendedForApproval"
}
```

---

## 5. Approval Inbox Proof

```bash
curl http://localhost:8080/tramai/sovereign/approvals
```

**Expected:** one pending approval work item with `status: PENDING_APPROVAL`.

---

## 6. Approval Decision Proof

Get the approval ID from step 5, then:

```bash
# Approve
curl -X POST http://localhost:8080/tramai/sovereign/approvals/{id}/approve \
  -H 'Content-Type: application/json' \
  -d '{"decisionBy":"lab-reviewer","reason":"approved in evidence capture"}'

# Or deny
curl -X POST http://localhost:8080/tramai/sovereign/approvals/{id}/deny \
  -H 'Content-Type: application/json' \
  -d '{"decisionBy":"lab-reviewer","reason":"denied in evidence capture"}'
```

**Expected:** `200 OK` with approval/denial confirmation.

---

## 7. Restart Durability Proof

1. Stop the app (`Ctrl+C`)
2. Start again:

```bash
./gradlew :examples:spring-sovereign-starter:bootRun \
  --args='--spring.profiles.active=sovereign-lab'
```

3. Check the inbox:

```bash
curl http://localhost:8080/tramai/sovereign/approvals
```

**Expected:** the same approval record persists after restart. JDBC persistence is active.

---

## 8. No Cloud Provider Proof

Run the CI smoke test:

```bash
./gradlew verifySovereignLabRuntimeSmoke
```

The smoke test verifies:
- `tramai.providers.local-lab-provider.type=openai`
- `base-url` contains `localhost` (not a cloud endpoint)
- No real LLM call is made during smoke — the provider URL points to an unused localhost endpoint

Expected: `BUILD SUCCESSFUL` and `verifySovereignLabRuntimeSmoke: sovereign lab runtime smoke tests passed.`

Also verify the lab profile YAML lists only local providers (`local-lab-provider`, `deterministic-local-provider`) in `allowed-providers`.

---

## Optional Local Model Benchmark

This benchmark is not part of CI and does not define production performance thresholds. It captures diagnostic latency information for the local machine, model, and endpoint used in this evidence session.

```bash
export TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK=true
export TRAMAI_LOCAL_BASE_URL=http://localhost:11434/v1
export TRAMAI_LOCAL_API_KEY=local-dev
export TRAMAI_LOCAL_MODEL=qwen2.5:7b
export TRAMAI_LOCAL_BENCHMARK_WARMUP=1
export TRAMAI_LOCAL_BENCHMARK_CALLS=3

./gradlew benchmarkSovereignLabLocalModel
```

**Expected output:**

```
BUILD SUCCESSFUL
Sovereign lab local-model benchmark report: ...
```

Attach the benchmark report to your evidence pack:

```
examples/spring-sovereign-starter/build/reports/sovereign-lab/local-model-benchmark/benchmark.json
```

---

## Evidence Template

Use the files under `evidence-template/` to record each capture session:

- `environment.md` — machine, Java, Gradle, local model details
- `run-log.md` — commands and terminal output
- `approval-flow.md` — claim submission, inbox, decision screenshots
- `restart-proof.md` — before/after restart persistence
- `no-cloud-proof.md` — zero-egress verification

Copy the template folder, fill in your session details, and attach terminal output or screenshots.

---

## Related

- [README.md](./README.md) — lab profile setup and quick start
- [verifySovereignLabRuntimeSmoke](../spring-sovereign-starter/) — CI smoke test
- [verifySovereignLabLocalModel](./local-model-smoke.sh) — opt-in local model invocation proof
