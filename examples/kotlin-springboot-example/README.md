# TramAI Kotlin Spring Boot Example

This example is the repository's smallest Spring application, but it is also expected to prove real product surface area rather than just compile.

It demonstrates, in one place:

- raw text operations
- streaming
- tool calling
- structured output
- orchestration with persisted checkpoints and active leases
- native-image proxy metadata generation

The example is intentionally narrow. It stays aligned with TramAI's core positioning: typed interface methods, explicit workflow state, and observable boundaries instead of agent-style abstractions.

## Build Resolution

The example uses `includeBuild("../..")`, so it resolves TramAI modules from the current repository checkout.

That means:

- you do not need to publish snapshots just to run the example from this repo
- the example acts as a testbench for the code currently on your branch
- `mavenLocal()` remains available as a fallback for standalone use

## Stack

- `dev.tramai:tramai-spring:0.1.0-SNAPSHOT`
- `dev.tramai:tramai-orchestration:0.1.0-SNAPSHOT`
- Spring Boot `3.4.5`
- Kotlin `2.3.0`
- Java `25`
- Ollama for local execution

## Models Used

- summary: `gemma4:e4b`
- streaming summary: `deepseek-r1:8b-64k`
- tool calling: `deepseek-r1:8b-64k`
- typed triage: `deepseek-r1:8b-64k`

## Endpoints

- `GET /`
  Small health and route inventory endpoint.
- `POST /invoice/summary`
  Returns one raw summary string.
- `POST /invoice/summary/stream`
  Streams summary tokens as `text/event-stream`.
- `POST /invoice/enrich`
  Uses TramAI tool calling to look up vendor details before returning a final answer.
- `POST /invoice/triage`
  Returns a typed `InvoiceTriageResult`.
- `POST /invoice/workflow`
  Runs a persisted workflow that composes summary, typed triage, routing, and optional vendor enrichment.
- `POST /invoice/workflow/start`
  Starts the workflow asynchronously and returns `202 Accepted` with a workflow id.
- `GET /invoice/workflow/result/{workflowId}`
  Returns workflow status (`PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`) and result when available.
- `GET /invoice/workflow/events/{workflowId}`
  Returns the recorded workflow lifecycle timeline (accepted, started, cancelled, completed, failed).
- `GET /invoice/workflow/list?limit=20`
  Returns recent workflow runs discovered from active execution and persisted checkpoints.
- `POST /invoice/workflow/cancel/{workflowId}`
  Cancels an active workflow run and marks it as `CANCELLED`.
- `GET /invoice/workflow/checkpoint/{workflowId}`
  Reads the current persisted workflow checkpoint.
- `POST /invoice/workflow/resume/{workflowId}`
  Resumes a workflow from its saved checkpoint.

## Async Timeout

Spring MVC async request timeout is configured to `90s` in the example:

- `spring.mvc.async.request-timeout=90s`

This is intentional so slower local model calls (especially tool loops and structured calls) do not hit the servlet timeout before TramAI finishes.

## What The Workflow Proves

The workflow is intentionally narrow:

1. summarize the invoice text
2. produce typed triage
3. branch on `needsImmediateAttention`
4. enrich vendor context only for escalation-worthy invoices
5. finalize one operator-facing brief

That is the boundary TramAI wants:

- the engine still owns provider execution, structured parsing, retries, fallback routing, and tool loops
- orchestration owns only typed workflow state, branching, persistence, and resume

## Persistence Layout

The example configures orchestration persistence like this:

- checkpoint store: `MarkdownWorkflowCheckpointStore`
- lease store: `FileWorkflowLeaseStore`
- state codec: a small Jackson-backed `InvoiceWorkflowStateCodec`
- default root: `build/tramai-example/workflows`

Completed runs keep their checkpoints so the files remain inspectable.
Workflow status and failure details are persisted into checkpoint metadata.

For a workflow id like `wf-1042`, the example writes:

```text
build/tramai-example/workflows/
└── invoice-review-workflow/
    └── wf-1042/
        ├── checkpoint.md
        └── lease.properties
```

The lease file is transient. It exists only while a node actively owns the workflow run.

## Requirements

- Ollama is running locally
- the example models are available

Example:

```bash
ollama pull gemma4:e4b
ollama pull deepseek-r1:8b-64k
ollama serve
```

## Run

From the repository root:

```bash
./gradlew -p examples/kotlin-springboot-example bootRun
```

## Try It

Health check:

```bash
curl -s http://localhost:8080/
```

Raw summary:

```bash
curl -s http://localhost:8080/invoice/summary \
  -H 'Content-Type: application/json' \
  -d '{
    "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue\nThe supplier says service suspension may start next week unless payment is confirmed."
  }'
```

Streaming summary:

```bash
curl -N http://localhost:8080/invoice/summary/stream \
  -H 'Content-Type: application/json' \
  -d '{
    "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue"
  }'
```

Tool calling:

```bash
curl -s http://localhost:8080/invoice/enrich \
  -H 'Content-Type: application/json' \
  -d '{
    "invoiceText": "Vendor: Acme\nInvoice: INV-123\nAmount: 1200 USD\nPlease verify terms."
  }' | jq
```

Typed triage:

```bash
curl -s http://localhost:8080/invoice/triage \
  -H 'Content-Type: application/json' \
  -d '{
    "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue\nThe supplier says service suspension may start next week unless payment is confirmed."
  }' | jq
```

Workflow run:

```bash
curl -s http://localhost:8080/invoice/workflow \
  -H 'Content-Type: application/json' \
  -d '{
    "workflowId": "wf-1042",
    "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue\nThe supplier says service suspension may start next week unless payment is confirmed."
  }' | jq
```

Workflow async start:

```bash
curl -s http://localhost:8080/invoice/workflow/start \
  -H 'Content-Type: application/json' \
  -d '{
    "workflowId": "wf-1042",
    "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue\nThe supplier says service suspension may start next week unless payment is confirmed."
  }' | jq
```

Workflow status and result:

```bash
curl -s http://localhost:8080/invoice/workflow/result/wf-1042 | jq
```

Workflow events timeline:

```bash
curl -s http://localhost:8080/invoice/workflow/events/wf-1042 | jq
```

Workflow list:

```bash
curl -s "http://localhost:8080/invoice/workflow/list?limit=20" | jq
```

Workflow cancel:

```bash
curl -s -X POST http://localhost:8080/invoice/workflow/cancel/wf-1042 | jq
```

Checkpoint inspection:

```bash
curl -s http://localhost:8080/invoice/workflow/checkpoint/wf-1042 | jq
```

Workflow resume:

```bash
curl -s -X POST http://localhost:8080/invoice/workflow/resume/wf-1042 | jq
```

## Native Image Metadata

The example ships a checked-in `proxy-config.json` for `InvoiceAnalyzer` and also includes a generator task so the file can be refreshed when the service contract changes.

Refresh the metadata with:

```bash
./gradlew -p examples/kotlin-springboot-example generateNativeImageProxyConfig
```

The generated file lives at:

```text
src/main/resources/META-INF/native-image/dev.tramai.examples/kotlin-springboot-example/proxy-config.json
```

This covers TramAI's JDK proxy requirement for the example's `@AiService` interface. It does not replace the rest of the native-image work your runtime stack may still need.

## Testing

Run the example tests with:

```bash
./gradlew -p examples/kotlin-springboot-example test
```

The tests cover:

- Spring proxy creation for the `@AiService`
- streaming behavior
- tool loop execution
- typed structured output
- workflow persistence and resume
- native-image proxy metadata staying in sync with the service contract
