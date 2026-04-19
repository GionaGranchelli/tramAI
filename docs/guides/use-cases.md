# Common Use Cases

Tramai is best suited to backend-oriented AI tasks that benefit from typed boundaries.

## 1. Data Extraction

Use Tramai when you need to convert unstructured text into typed data.

Examples:

- invoice field extraction
- ticket triage metadata extraction
- policy or contract metadata extraction
- alert enrichment

Typical return type:

```kotlin
data class InvoiceFields(
    val vendor: String,
    val invoiceNumber: String,
    val amount: Double,
)
```

## 2. Classification

Tramai works well for constrained classification tasks.

Examples:

- incident severity
- support routing
- sentiment labeling
- policy approval categories

Typical return type:

```kotlin
data class Classification(
    val label: String,
    val confidence: Double,
)
```

## 3. Summarization With Structure

Plain summarization is easy, but structured summarization is usually more useful in real systems.

Examples:

- meeting recap plus actions
- customer feedback summary plus themes
- change request analysis plus risk items

## 4. Internal Automation Services

Tramai fits well when AI becomes an internal dependency of a normal service layer.

Examples:

- billing workflow helpers
- FinOps analysis
- support automation
- internal developer tooling

## 5. Local Development With Ollama

Use Ollama when you want:

- low-friction local experiments
- no external cloud dependency during development
- reproducible local testing of basic flows

Use the same interface contract, then swap the provider.

## 6. Multi-Provider Apps

Tramai supports a practical split:

- OpenAI or Anthropic for production cloud workflows
- Ollama for local development
- compatible endpoint for internal routing or gateway control

Because provider mapping is explicit, you can keep the interface stable while changing the backend.

## 7. Testing AI-Dependent Business Logic

Tramai is intentionally good at this.

Use `MockAiProvider` when you want to test:

- whether your service calls the right Tramai method
- whether structured retries happen
- whether your application logic behaves correctly after receiving a typed AI result

## Where Tramai Is Less Suitable Today

Tramai is not yet the best fit for:

- streaming-first chat UIs
- tool-calling agents
- long-lived memory workflows
- highly dynamic multi-tool orchestration

Those are future-growth areas rather than current strengths.
