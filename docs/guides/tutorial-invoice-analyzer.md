# Tutorial: Build an Invoice Analyzer

This tutorial walks through a realistic first TramAI use case from start to finish:

- add the right dependencies
- define one typed AI service
- wire one provider
- move from raw text to structured output
- test the result deterministically

The goal is not to show every feature. The goal is to get one production-shaped use case working in a way that feels natural to a backend engineer.

## What You Are Building

You will build an `InvoiceAnalyzer` that turns messy invoice text into a typed result:

```kotlin
data class InvoiceDecision(
    val status: String,
    val vendor: String?,
    val amountText: String?,
    val nextAction: String,
)
```

The final application code will call:

```kotlin
val result = analyzer.analyze(invoiceText)
```

and receive a typed object instead of a raw model string.

## Step 1: Add Dependencies

For a plain JVM app, start with:

### Gradle

```kotlin
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.2.0"))
    implementation("dev.tramai:tramai-standalone")
    implementation("dev.tramai:tramai-openai")

    testImplementation(platform("dev.tramai:tramai-bom:0.2.0"))
    testImplementation("dev.tramai:tramai-testing")
}
```

### Maven

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.tramai</groupId>
      <artifactId>tramai-bom</artifactId>
      <version>0.2.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-standalone</artifactId>
  </dependency>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-openai</artifactId>
  </dependency>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-testing</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

If your app is Spring Boot, swap `tramai-standalone` for `tramai-spring`.

## Step 2: Start With A Raw String Operation

Begin with the smallest working version.

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation

@AiService
interface InvoiceAnalyzer {
    @Operation(
        prompt = "Read the invoice text and summarize the payment situation in one short sentence.",
        model = "gpt-4o",
    )
    suspend fun summarize(invoiceText: String): String
}
```

This first step is useful because it proves:

- your dependencies are correct
- your provider is wired correctly
- your model mapping works

## Step 3: Wire The Provider

For a plain JVM app:

```kotlin
import dev.tramai.openai.OpenAiProvider
import dev.tramai.standalone.Tramai

suspend fun main() {
    val tramai = Tramai {
        provider(
            OpenAiProvider(apiKey = System.getenv("OPENAI_API_KEY")),
            name = "openai",
        )
        model("gpt-4o", "openai")
    }

    val analyzer = tramai.create<InvoiceAnalyzer>()
    val summary = analyzer.summarize(
        """
        Vendor: Northwind Power
        Invoice: INV-1042
        Amount due: 4820 USD
        Due date: 2026-04-30
        Status: 12 days overdue
        """.trimIndent(),
    )

    println(summary)
}
```

If this works, stop and verify it before moving on. Structured output is much easier to debug after the basic provider path is proven.

## Step 4: Upgrade To Structured Output

Now switch the operation from raw text to a typed contract.

```kotlin
data class InvoiceDecision(
    val status: String,
    val vendor: String?,
    val amountText: String?,
    val nextAction: String,
)

@AiService
interface InvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice text and return a structured payment decision.",
        model = "gpt-4o",
    )
    suspend fun analyze(invoiceText: String): InvoiceDecision
}
```

That is the core TramAI move:

- before: application receives a raw string
- after: application receives a typed DTO

At that point TramAI owns:

- prompt contract injection for the structured result
- JSON extraction
- deserialization
- validation
- retry on malformed structured responses

## Step 5: Add Lightweight Constraints

If fields drive business logic, constrain them.

```kotlin
import dev.tramai.core.annotations.AiDescription

data class InvoiceDecision(
    @property:AiDescription("One of CURRENT, DUE_SOON, OVERDUE, DISPUTED, or UNKNOWN")
    val status: String,

    @property:AiDescription("Vendor name when present in the input")
    val vendor: String?,

    @property:AiDescription("Amount exactly as it appears in the invoice text")
    val amountText: String?,

    @property:AiDescription("Primary next action the accounts-payable team should take")
    val nextAction: String,
)
```

Do not overdesign the first version. Keep the DTO small and precise.

## Step 6: Use The Typed Result In Application Code

Now your application logic can stay normal and deterministic:

```kotlin
suspend fun reviewInvoice(analyzer: InvoiceAnalyzer, invoiceText: String): String {
    val decision = analyzer.analyze(invoiceText)

    return when (decision.status) {
        "OVERDUE" -> "Escalate invoice for immediate review"
        "DISPUTED" -> "Route invoice to investigation queue"
        else -> "Handle in normal payment workflow"
    }
}
```

Notice what is missing:

- no manual JSON parsing
- no response cleanup regex
- no ad hoc retry loop in business code

## Step 7: Test It Deterministically

This is the first test you want:

```kotlin
import dev.tramai.testing.MockAiProvider
import dev.tramai.standalone.Tramai
import dev.tramai.openai.OpenAiProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class InvoiceAnalyzerTest {
    @Test
    fun `returns typed invoice decision`() = runBlocking {
        val provider = MockAiProvider {
            onMethod("analyze") respondWith """
                {
                  "status": "OVERDUE",
                  "vendor": "Northwind Power",
                  "amountText": "4820 USD",
                  "nextAction": "ESCALATE"
                }
            """.trimIndent()
        }

        val tramai = Tramai {
            provider(provider, name = "mock", default = true)
            model("gpt-4o", "mock")
        }

        val analyzer = tramai.create<InvoiceAnalyzer>()
        val result = analyzer.analyze("Vendor: Northwind Power\nInvoice: INV-1042")

        assertEquals(
            InvoiceDecision(
                status = "OVERDUE",
                vendor = "Northwind Power",
                amountText = "4820 USD",
                nextAction = "ESCALATE",
            ),
            result,
        )
    }
}
```

This proves your application code consumes the typed contract correctly without requiring network access.

## Step 8: Test Recovery Behavior

Your second useful test is malformed structured output followed by recovery:

```kotlin
val provider = MockAiProvider {
    onMethod("analyze") respondWith "not json"
    onMethod("analyze") respondWith """
        {
          "status": "OVERDUE",
          "vendor": "Northwind Power",
          "amountText": "4820 USD",
          "nextAction": "ESCALATE"
        }
    """.trimIndent()
}
```

That lets you verify that TramAI's structured retry loop is doing the work instead of your application code.

## Step 9: Spring Boot Variant

If you are in Spring Boot, the service contract stays the same.

You change only the runtime wiring:

```kotlin
@AiService
interface InvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice text and return a structured payment decision.",
        model = "gpt-4o",
    )
    suspend fun analyze(invoiceText: String): InvoiceDecision
}

@Service
class BillingService(
    private val invoiceAnalyzer: InvoiceAnalyzer,
) {
    suspend fun review(invoiceText: String): InvoiceDecision = invoiceAnalyzer.analyze(invoiceText)
}
```

and the configuration:

```yaml
tramai:
  default-provider: openai
  models:
    gpt-4o: openai
  providers:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com/v1
```

That is one of TramAI's main design goals: the typed service contract should survive the runtime choice.

## What You Built

At this point you have:

- one typed interface
- one provider mapping
- one structured output contract
- one deterministic test
- no parsing logic in application code

That is already a good first production shape for a backend AI integration.

## Good Next Steps

From here, take the next step that matches your use case:

1. add validation annotations if fields drive important decisions
2. add `tramai-testing` assertions for retry behavior
3. add `tramai-observability` if you need tracing or metrics
4. add `tramai-orchestration` only if the invoice process becomes truly multi-step and persisted

## Related Guides

- [Getting Started](./getting-started.md)
- [Providers and Model Routing](./providers.md)
- [Structured Output](./structured-output.md)
- [Testing TramAI Code](./testing.md)
- [Spring Boot Integration](./spring-boot.md)
