package dev.tramai.security.evidence

/**
 * 0.7.1d: governed-run attribution at the evidence boundary.
 *
 * Why this stays stringly-typed: [RuntimeEvidenceRecord] is a public runtime-evidence.v1
 * contract with an allowlisted, per-family-validated metadata extension point. Adding a typed
 * identity field would change that public ABI and couple tramai-security to the core identity
 * model, so the governed identity travels as reserved metadata keys — the same mechanism already
 * used for checkpoint attribution — and the modules that actually hold the typed identity
 * translate it at their own boundary.
 *
 * The run component is deliberately absent from [metadataKeys]: [RuntimeEvidenceRecord.workflowRunId]
 * is the single canonical run identifier, and duplicating it here would create a second run
 * identifier that could disagree with the first. Only the deployment tuple is added, so
 * `workflowRunId` plus these five keys together are the complete governed identity.
 *
 * Atomicity: evidence metadata is either wholly attributed or not attributed at all. Zero keys is
 * legacy evidence and stays valid; a partially present tuple is corruption, never a silent
 * downgrade, and a complete tuple without the canonical run identity is likewise rejected.
 */
internal object RuntimeEvidenceAttribution {
    /** Deployment-tuple keys that, with a non-blank `workflowRunId`, form a complete governed identity. */
    val metadataKeys =
        setOf(
            "identity.workloadId",
            "identity.configurationId",
            "identity.configurationVersion",
            "identity.environmentId",
            "identity.deploymentId",
        )

    /**
     * Overlays framework-owned [attribution] on top of [metadata] so caller/event metadata can
     * never substitute or shadow identity.
     *
     * Callers must overlay before the canonical payload digest is computed, so the identity
     * participates in the digest the record advertises.
     */
    fun merge(
        metadata: Map<String, String>,
        attribution: Map<String, String>,
    ): Map<String, String> {
        val unknown = attribution.keys - metadataKeys
        require(unknown.isEmpty()) {
            "evidence attribution may only carry governed identity keys, got: $unknown"
        }
        return metadata + attribution
    }

    /**
     * Validates attribution atomically against the canonical run identity: zero keys is legacy,
     * all five keys require a non-blank `workflowRunId`, and anything in between is rejected.
     */
    fun validate(
        workflowRunId: String?,
        metadata: Map<String, String>,
    ) {
        val present = metadata.keys.filter { it in metadataKeys }
        when {
            present.isEmpty() -> {
                return
            }

            present.size != metadataKeys.size -> {
                reject("partial governed attribution: ${present.sorted()}")
            }

            workflowRunId.isNullOrBlank() -> {
                reject("attribution without the canonical workflowRunId")
            }
        }
    }

    /**
     * Refuses ambiguous evidence rather than emitting a record whose attribution cannot be trusted.
     */
    private fun reject(cause: String): Nothing = error("$cause; refusing to emit ambiguous governed evidence")
}
