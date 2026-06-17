package dev.tramai.examples.spring

import dev.tramai.sovereign.SovereignTramaiRuntime
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Verifies that the sovereign Spring Boot starter example works end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SpringSovereignStarterExampleTest {

    @Autowired
    private lateinit var runtime: SovereignTramaiRuntime

    @Test
    fun `context loads with SovereignTramaiRuntime`() {
        assertThat(runtime).isNotNull
    }

    @Test
    fun `analyzeInvoice returns deterministic result through sovereign runtime`() {
        val service = runtime.create(InvoiceAiService::class)

        val result = runBlocking {
            service.analyzeInvoice("""
                Invoice INV-2026-001 for €12,500 from Vendor Alpha.
                Contains customer reference NL-RESTRICTED-8842.
                Payment requested within 7 days.
            """.trimIndent())
        }

        assertThat(result.summary)
            .isEqualTo("Invoice requires review before payment.")
        assertThat(result.riskLevel)
            .isEqualTo("MEDIUM")
        assertThat(result.detectedRisks)
            .containsExactly(
                "Restricted customer reference present",
                "High-value invoice",
                "Short payment window",
            )
        assertThat(result.recommendedAction)
            .isEqualTo("Route to finance approval workflow")
    }
}
