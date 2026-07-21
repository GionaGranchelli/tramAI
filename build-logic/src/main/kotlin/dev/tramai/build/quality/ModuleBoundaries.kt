package dev.tramai.build.quality

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream

/**
 * Parses and evaluates module boundary rules (config/quality/module-boundaries.yml).
 * Used by the verifier to reject forbidden architectural edges.
 */
class ModuleBoundaries(private val rootDir: File) {

    data class ForbiddenEdgeRule(
        val fromLayer: String? = null,
        val toLayer: String? = null,
        val fromPublished: Boolean? = null,
        val toPublishability: String? = null,
        val selfEdge: Boolean? = null,
        val reason: String
    )

    data class AllowedEdgeRule(
        val fromLayer: String? = null,
        val toLayer: String? = null,
        val fromModule: String? = null,
        val toModule: String? = null,
        val reason: String
    )

    data class BoundaryResult(
        val forbiddenEdges: List<ForbiddenEdgeRule>,
        val allowedEdges: List<AllowedEdgeRule>,
        val errors: List<VerificationDiagnostic>
    )

    /** Parsed rules, stored as instance fields populated during parse(). */
    private var forbiddenEdges: List<ForbiddenEdgeRule> = emptyList()
    private var allowedEdges: List<AllowedEdgeRule> = emptyList()

    /**
     * Create a ModuleBoundaries with pre-set parsed rules (for testing).
     */
    fun fromResult(result: BoundaryResult): ModuleBoundaries {
        val mb = ModuleBoundaries(rootDir)
        mb.forbiddenEdges = result.forbiddenEdges
        mb.allowedEdges = result.allowedEdges
        return mb
    }

    fun parse(): BoundaryResult {
        val file = File(rootDir, "config/quality/module-boundaries.yml")
        if (!file.isFile) {
            return BoundaryResult(emptyList(), emptyList(), listOf(
                VerificationDiagnostic.failure(DiagnosticCode.FORBIDDEN_LAYER_EDGE,
                    "Boundary rules not found: ${file.absolutePath}")
            ))
        }

        val errors = mutableListOf<VerificationDiagnostic>()
        val parsedForbidden = mutableListOf<ForbiddenEdgeRule>()
        val parsedAllowed = mutableListOf<AllowedEdgeRule>()

        try {
            val yaml = Yaml()
            val root = FileInputStream(file).use { yaml.load<Map<String, Any>>(it) }

            val forbiddenRaw = root["forbiddenEdges"] as? List<Map<String, Any>> ?: emptyList()
            for ((index, entry) in forbiddenRaw.withIndex()) {
                val reason = entry["reason"]?.toString() ?: ""

                if (reason.isBlank()) {
                    errors.add(VerificationDiagnostic.failure(
                        DiagnosticCode.FORBIDDEN_LAYER_EDGE,
                        "Forbidden edge rule $index: reason is blank"))
                    continue
                }

                val selfEdge = entry["selfEdge"] as? Boolean
                if (selfEdge == true) {
                    parsedForbidden.add(ForbiddenEdgeRule(selfEdge = true, reason = reason))
                    continue
                }

                parsedForbidden.add(
                    ForbiddenEdgeRule(
                        fromLayer = entry["fromLayer"]?.toString(),
                        toLayer = entry["toLayer"]?.toString(),
                        fromPublished = entry["fromPublished"] as? Boolean,
                        toPublishability = entry["toPublishability"]?.toString(),
                        reason = reason
                    )
                )
            }

            val allowedRaw = root["knownAllowedEdges"] as? List<Map<String, Any>> ?: emptyList()
            for ((index, entry) in allowedRaw.withIndex()) {
                val reason = entry["reason"]?.toString() ?: ""
                if (reason.isBlank()) {
                    errors.add(VerificationDiagnostic.failure(
                        DiagnosticCode.FORBIDDEN_LAYER_EDGE,
                        "Allowed edge rule $index: reason is blank"))
                    continue
                }
                parsedAllowed.add(
                    AllowedEdgeRule(
                        fromLayer = entry["fromLayer"]?.toString(),
                        toLayer = entry["toLayer"]?.toString(),
                        fromModule = entry["fromModule"]?.toString(),
                        toModule = entry["toModule"]?.toString(),
                        reason = reason
                    )
                )
            }

        } catch (e: Exception) {
            errors.add(VerificationDiagnostic.failure(
                DiagnosticCode.FORBIDDEN_LAYER_EDGE,
                "Failed to parse boundary rules: ${e.message}"))
        }

        // Store parsed rules as instance fields
        forbiddenEdges = parsedForbidden
        allowedEdges = parsedAllowed

        return BoundaryResult(parsedForbidden, parsedAllowed, errors)
    }

    /**
     * Check whether a dependency edge is forbidden by the rules.
     *
     * @param fromPath The source module Gradle path (e.g. ":tramai-core")
     * @param toPath The target module Gradle path
     * @param catalog The loaded module catalog for layer/publishability lookups
     * @return A VerificationDiagnostic if the edge is forbidden, null if allowed.
     */
    fun checkEdge(
        fromPath: String,
        toPath: String,
        catalog: ModuleCatalog
    ): VerificationDiagnostic? {
        // Self-edge check
        if (fromPath == toPath) {
            return VerificationDiagnostic.failure(
                DiagnosticCode.SELF_DEPENDENCY,
                "Module $fromPath depends on itself"
            )
        }

        val fromLayer = catalog.layerFor(fromPath) ?: return null
        val toLayer = catalog.layerFor(toPath) ?: return null
        val fromPublished = catalog.isPublished(fromPath)
        val toPublishability = catalog.publishabilityFor(toPath) ?: "internal"

        for (rule in forbiddenEdges) {
            if (rule.selfEdge == true) continue // handled above

            val layerMatch = rule.fromLayer == null || (rule.fromLayer == fromLayer)
            val toLayerMatch = rule.toLayer == null || (rule.toLayer == toLayer) || rule.toLayer == "*"
            val publishedMatch = rule.fromPublished == null || (rule.fromPublished == fromPublished)
            val pubMatch = rule.toPublishability == null || (rule.toPublishability == toPublishability)

            // Check allowed edge exceptions first (supports layer and module matching)
            val isAllowed = allowedEdges.any { edge ->
                val fromOk = (edge.fromLayer == null || edge.fromLayer == fromLayer || edge.fromLayer == "*") &&
                    (edge.fromModule == null || edge.fromModule == fromPath)
                val toOk = (edge.toLayer == null || edge.toLayer == toLayer || edge.toLayer == "*") &&
                    (edge.toModule == null || edge.toModule == toPath)
                fromOk && toOk
            }

            if (layerMatch && toLayerMatch && publishedMatch && pubMatch && !isAllowed) {
                return VerificationDiagnostic.failure(
                    DiagnosticCode.FORBIDDEN_LAYER_EDGE,
                    "Forbidden edge: $fromPath ($fromLayer) -> $toPath ($toLayer): ${rule.reason}",
                    modulePath = fromPath
                )
            }
        }

        return null
    }
}
