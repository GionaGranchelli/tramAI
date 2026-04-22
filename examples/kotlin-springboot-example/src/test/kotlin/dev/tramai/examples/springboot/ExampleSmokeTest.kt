package dev.tramai.examples.springboot

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Narrow release-gating smoke tests for the example as a downstream consumer.
 *
 * This deliberately avoids using the full example test suite as a publish blocker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(ExampleApplicationTest.TestProviderConfiguration::class)
class ExampleSmokeTest {
    @Autowired
    private lateinit var analyzer: InvoiceAnalyzer

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var testProvider: ExampleTestProvider

    @BeforeEach
    fun resetProvider() {
        testProvider.reset()
    }

    @Test
    fun contextLoadsWithTramaiAiServiceBean() {
        assertThat(analyzer).isNotNull
    }

    @Test
    fun summaryEndpointReturnsRawTextResponse() {
        asyncJson(
            post("/invoice/summary")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"invoiceText":"Vendor: Northwind Power\nInvoice: INV-1042"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary").value("Northwind Power invoice INV-1042 needs review."))
    }

    @Test
    fun triageEndpointReturnsTypedStructuredResponse() {
        asyncJson(
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

    private fun asyncJson(requestBuilder: org.springframework.test.web.servlet.RequestBuilder): ResultActions {
        val mvcResult = mockMvc.perform(requestBuilder)
            .andExpect(request().asyncStarted())
            .andReturn()
        return mockMvc.perform(asyncDispatch(mvcResult))
    }
}
