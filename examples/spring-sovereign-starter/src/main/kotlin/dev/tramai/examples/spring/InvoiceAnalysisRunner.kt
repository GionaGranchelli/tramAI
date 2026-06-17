package dev.tramai.examples.spring

import dev.tramai.sovereign.SovereignTramaiRuntime
import kotlinx.coroutines.runBlocking
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

/**
 * Runs the sovereign invoice analysis when the application starts.
 *
 * Injects [SovereignTramaiRuntime] (auto-configured by the starter),
 * creates the typed [InvoiceAiService] proxy, and executes it.
 */
@Component
class InvoiceAnalysisRunner(
    private val runtime: SovereignTramaiRuntime,
) : CommandLineRunner {

    override fun run(vararg args: String) {
        runBlocking {
            val service = runtime.create(InvoiceAiService::class)

            val result = service.analyzeInvoice(
                """
                Invoice INV-2026-001 for €12,500 from Vendor Alpha.
                Contains customer reference NL-RESTRICTED-8842.
                Payment requested within 7 days.
                """.trimIndent(),
            )

            println("Sovereign invoice analysis result:")
            println(result)
        }
    }
}
