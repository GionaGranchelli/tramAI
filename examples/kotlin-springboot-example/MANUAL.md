# Example Manual

This manual explains the example as a backend application, not just as a list of endpoints.

## Request Flow

The example is intentionally layered:

1. `api`
   Spring MVC controllers receive HTTP requests and return API DTOs.
2. `application`
   A small facade translates API use cases into TramAI calls.
3. `ai`
   `InvoiceAnalyzer` defines the typed TramAI contract.
4. `tools`
   `VendorTools` contains deterministic application logic used by tool calling.
5. `workflow`
   `InvoiceWorkflowCoordinator` composes multiple TramAI operations with persisted state.

That separation matters because Tramai is meant to fit into normal service architecture instead of replacing it.

## Capability Walkthrough

### 1. Raw Text

Endpoint:

```text
POST /invoice/summary
```

This is the smallest TramAI path in the example:

- one request DTO
- one `@AiService` method
- one plain `String` result

Use it when you want a simple generated explanation or summary and your application code does not need typed downstream fields.

### 2. Streaming

Endpoint:

```text
POST /invoice/summary/stream
```

This shows that the same typed service style can expose streaming behavior without inventing a separate framework abstraction.

The controller returns `text/event-stream`, while TramAI still owns the provider interaction.

### 3. Tool Calling

Endpoint:

```text
POST /invoice/enrich
```

This path demonstrates a critical boundary:

- TramAI decides when to call the tool
- the application still owns what the tool actually does

In this example, `vendor_lookup` is backed by a normal Spring component. In a real application that component would likely call a database, CRM, or internal service.

### 4. Structured Output

Endpoint:

```text
POST /invoice/triage
```

This path shows TramAI's main library value:

- model output is constrained to a typed contract
- the response is parsed into a DTO
- the rest of the application can consume the result without parsing raw model text

The example intentionally keeps a distinction between:

- model-facing structured types in `domain`
- API-facing response types in `api`

That keeps model quirks from leaking directly into the public HTTP contract.

### 5. Workflow Orchestration

Endpoints:

```text
POST /invoice/workflow
POST /invoice/workflow/start
GET  /invoice/workflow/result/{workflowId}
GET  /invoice/workflow/checkpoint/{workflowId}
POST /invoice/workflow/resume/{workflowId}
GET  /invoice/workflow/events/{workflowId}
POST /invoice/workflow/cancel/{workflowId}
```

The workflow is intentionally narrow:

1. summarize
2. triage
3. branch based on urgency
4. enrich vendor context only when escalation is needed
5. finalize an operator brief

This shows how TramAI composes with explicit orchestration rather than hiding multi-step control flow inside prompts.

## Inspecting Persistence

After running a workflow, inspect:

```bash
cat build/tramai-example/workflows/invoice-review-workflow/<workflowId>/checkpoint.md
```

The saved checkpoint lets you audit:

- current step index
- last completed step
- encoded workflow state
- workflow status metadata

## Suggested Exploration Order

1. Start the app.
2. Call `GET /`.
3. Call `POST /invoice/summary`.
4. Call `POST /invoice/triage`.
5. Call `POST /invoice/enrich`.
6. Run `POST /invoice/workflow`.
7. Inspect the saved checkpoint on disk.
8. Try `POST /invoice/workflow/start` and follow it with the result and events endpoints.

## Notes For Readers

- The example uses Ollama in `application.yml` because it keeps the demo local.
- The test suite replaces the provider with a deterministic fake so behavior stays auditable.
- The example is intentionally not a chat app. It is shaped like a backend workflow because that is TramAI's target use case.
