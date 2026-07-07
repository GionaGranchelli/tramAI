package dev.tramai.examples.governed

enum class ClaimRisk {
    LOW,
    MEDIUM,
    HIGH,
    RESTRICTED,
}

data class ClaimInput(
    val claimId: String,
    val amount: Double,
    val type: String,
    val description: String,
)

data class ClaimClassification(
    val risk: ClaimRisk,
    val category: String,
    val confidence: Double,
)

data class ClaimTriageState(
    val claim: ClaimInput,
    val classification: ClaimClassification? = null,
    val approved: Boolean = false,
    val result: ClaimTriageResult? = null,
)

data class ClaimTriageResult(
    val status: String,
    val reason: String,
)
