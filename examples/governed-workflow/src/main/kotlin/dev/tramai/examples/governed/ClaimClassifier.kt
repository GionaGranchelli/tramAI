package dev.tramai.examples.governed

interface ClaimClassifier {
    suspend fun classify(claim: ClaimInput): ClaimClassification
}

class DeterministicClaimClassifier : ClaimClassifier {
    override suspend fun classify(claim: ClaimInput): ClaimClassification =
        when {
            claim.type == "restricted" -> ClaimClassification(
                risk = ClaimRisk.RESTRICTED,
                category = "restricted",
                confidence = 1.0,
            )
            claim.amount >= 10_000.0 -> ClaimClassification(
                risk = ClaimRisk.HIGH,
                category = "large-claim",
                confidence = 0.95,
            )
            else -> ClaimClassification(
                risk = ClaimRisk.LOW,
                category = "standard",
                confidence = 0.9,
            )
        }
}
