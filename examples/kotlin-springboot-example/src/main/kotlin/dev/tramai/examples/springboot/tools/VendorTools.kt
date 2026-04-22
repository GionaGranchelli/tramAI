package dev.tramai.examples.springboot.tools

import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiTool
import org.springframework.stereotype.Component

/**
 * Deliberately deterministic application tool used by the example.
 *
 * This keeps the demo honest: Tramai orchestrates the tool loop, but the actual business action
 * remains a normal application component that could be swapped for a repository or CRM client.
 */
@Component
class VendorTools {
    @AiTool(
        name = "vendor_lookup",
        description = "Looks up details for a vendor by name, including reliability and terms.",
    )
    fun lookupVendor(input: VendorLookupInput): VendorDetails {
        return when (input.vendorName.lowercase()) {
            "acme" -> VendorDetails("Acme Corp", 4.8, "NET-30")
            "globex" -> VendorDetails("Globex", 3.2, "NET-15")
            else -> VendorDetails(input.vendorName, 4.0, "NET-30 (Standard)")
        }
    }
}

data class VendorLookupInput(
    @property:AiDescription("The name of the vendor to look up")
    val vendorName: String,
)

data class VendorDetails(
    val fullName: String,
    val reliabilityScore: Double,
    val paymentTerms: String,
)
