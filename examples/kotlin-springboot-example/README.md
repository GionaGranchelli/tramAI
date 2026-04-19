# Kotlin Spring Boot Example

This example is intentionally small, but it shows something that stands out:

- a normal Spring Boot application injects an Tramai `@AiService`
- one endpoint returns plain text
- one endpoint returns a typed Kotlin object
- the typed endpoint uses enums, a nested data class, a boolean, and a constrained numeric field

That contrast is the point. The raw path is easy to get from any LLM wrapper. The typed path is where Tramai becomes application infrastructure instead of just model plumbing.

## Stack

- `dev.tramai:tramai-spring:0.1.0-SNAPSHOT` from `mavenLocal()`
- Spring Boot `3.4.5`
- Kotlin `2.3.0`
- Java `25`
- Ollama for local execution

## Models Used

This example keeps the chosen local models:

- summary: `gemma4:e4b`
- typed triage: `deepseek-r1:8b-64k`

## What The App Does

The example exposes:

- `POST /invoice/summary`
  Returns one free-form sentence.
- `POST /invoice/triage`
  Returns a typed `InvoiceTriageResult` object.

Both endpoints accept the same request body:

```json
{
  "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue\nThe supplier says service suspension may start next week unless payment is confirmed."
}
```

## Why This Shows Tramai Better

The typed endpoint demonstrates that Tramai can:

- turn messy natural language into a Kotlin object graph
- validate the output against enums and numeric constraints
- return something your controller can expose directly without a second parsing layer
- surface structured failure details when a local model misses the schema

## Requirements

- Tramai has been published to your local Maven repository
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

Raw text path:

```bash
curl -s http://localhost:8080/invoice/summary \
  -H 'Content-Type: application/json' \
  -d '{
    "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue\nThe supplier says service suspension may start next week unless payment is confirmed."
  }'
```

Typed path:

```bash
curl -s http://localhost:8080/invoice/triage \
  -H 'Content-Type: application/json' \
  -d '{
    "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30\nStatus: 12 days overdue\nThe supplier says service suspension may start next week unless payment is confirmed."
  }' | jq
```

Expected shape:

```json
{
  "summary": "Invoice INV-1042 is overdue and needs prompt review.",
  "status": "OVERDUE",
  "priority": "HIGH",
  "needsImmediateAttention": true,
  "riskScore": 4,
  "facts": {
    "invoiceId": "INV-1042",
    "vendor": "Northwind Power",
    "amountDueText": "4820 USD",
    "dueDate": "2026-04-30"
  },
  "nextStep": "ESCALATE"
}
```

## Timeout And Retry Note

The typed endpoint has a larger timeout budget because local structured output is slower than a plain text summary.

Current example settings:

- summary timeout: `60_000` ms
- summary provider retries: `0`
- triage timeout: `180_000` ms
- triage structured retries: `2`
- triage provider retries: `0`

Provider retries are disabled in the example because a local-model timeout usually means "this call is expensive", not "a transient remote API blip". Structured retries remain enabled because they are useful when the model returns almost-correct JSON.

## Error Handling

If the model still fails to satisfy the typed schema, the example now returns JSON errors instead of a raw stack trace.

Structured-output failure shape:

```json
{
  "error": "structured_output_failed",
  "message": "Structured output parsing failed after 3 attempt(s)",
  "validationError": "...",
  "attemptCount": 3,
  "lastRawResponse": "..."
}
```
