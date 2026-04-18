package io.aurora.examples.springboot

import io.aurora.core.model.ModelRequest
import io.aurora.core.model.ModelResponse
import io.aurora.core.provider.ModelProvider
import io.aurora.testing.MockAiProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Verifies that the example application can start and that the standout raw and typed HTTP paths work end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(ExampleApplicationTest.TestProviderConfiguration::class)
class ExampleApplicationTest {
    @Autowired
    private lateinit var analyzer: InvoiceAnalyzer

    @Autowired
    private lateinit var mockMvc: MockMvc

    /**
     * The example is valid if Spring can resolve the generated Aurora proxy.
     */
    @Test
    fun `context loads with aurora ai service bean`() {
        assertThat(analyzer).isNotNull
    }

    @Test
    fun `summary endpoint returns raw text response`() {
        mockMvc.perform(
            post("/invoice/summary")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"invoiceText":"Vendor: Northwind Power\nInvoice: INV-1042"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary").value("Northwind Power invoice INV-1042 needs review."))
    }

    @Test
    fun `triage endpoint returns typed structured response`() {
        mockMvc.perform(
            post("/invoice/triage")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {
                          "invoiceText": "Vendor: Northwind Power\nInvoice: INV-1042\nAmount due: 4820 USD\nDue date: 2026-04-30"
                        }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary").value("Invoice INV-1042 from Northwind Power is overdue."))
            .andExpect(jsonPath("$.status").value("OVERDUE"))
            .andExpect(jsonPath("$.priority").value("HIGH"))
            .andExpect(jsonPath("$.needsImmediateAttention").value(true))
            .andExpect(jsonPath("$.riskScore").value(4))
            .andExpect(jsonPath("$.facts.invoiceId").value("INV-1042"))
            .andExpect(jsonPath("$.facts.vendor").value("Northwind Power"))
            .andExpect(jsonPath("$.facts.amountDueText").value("4820 USD"))
            .andExpect(jsonPath("$.facts.dueDate").value("2026-04-30"))
            .andExpect(jsonPath("$.nextStep").value("ESCALATE"))
    }

    @Test
    fun `health endpoint describes the example routes`() {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.application").value("kotlin-springboot-example"))
            .andExpect(jsonPath("$.typedEndpoint").value("POST /invoice/triage"))
    }

    @TestConfiguration
    open class TestProviderConfiguration {
        @Bean
        open fun ollamaProvider(): ModelProvider {
            val delegate = MockAiProvider {
                onMethod("summarize") respondWith "Northwind Power invoice INV-1042 needs review."
                onMethod("triage") respondWith """
                    {
                      "summary": "Invoice INV-1042 from Northwind Power is overdue.",
                      "status": { "name": "OVERDUE", "ordinal": 2 },
                      "priority": { "name": "HIGH", "ordinal": 2 },
                      "needsImmediateAttention": true,
                      "riskScore": 4,
                      "facts": {
                        "invoiceId": "INV-1042",
                        "vendor": "Northwind Power",
                        "amountDueText": "4820 USD",
                        "dueDate": "2026-04-30"
                      },
                      "nextStep": { "name": "ESCALATE", "ordinal": 4 }
                    }
                """.trimIndent()
            }

            return object : ModelProvider {
                override suspend fun complete(request: ModelRequest): ModelResponse = delegate.complete(request)

                override fun providerId(): String = "ollama"
            }
        }
    }
}
