# TramAI Sovereign Lab — Physical Local-Model Testing

This profile lets you run TramAI Sovereign Runtime **locally** on your machine with:

- PostgreSQL for durable persistence
- A local model endpoint (OpenAI-compatible: Ollama, llama.cpp, vLLM, LM Studio, LocalAI)
- Approval workflow fully wired
- REST control plane + reviewer UI enabled
- **Zero egress** — no cloud providers called

No Docker, no CI, no cloud. Just your machine, a local model, and PostgreSQL.

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

## Files

| File | Purpose |
|------|---------|
| `docker-compose.yml` | PostgreSQL for local persistence |
| `README.md` | This file |
| `application-sovereign-lab.yml` | Spring Boot profile (in the example module) |
