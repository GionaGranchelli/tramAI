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
        val reason: String
    )

    data class BoundaryResult(
        val forbiddenEdges: List<ForbiddenEdgeRule>,
        val allowedEdges: List<AllowedEdgeRule>,
        val errors: List<String>
    )

    fun parse(): BoundaryResult {
        val file = File(rootDir, "config/quality/module-boundaries.yml")
        if (!file.isFile) {
            return BoundaryResult(emptyList(), emptyList(), listOf("Boundary rules not found: ${file.absolutePath}"))
        }

        val errors = mutableListOf<String>()
        val forbiddenEdges = mutableListOf<ForbiddenEdgeRule>()
        val allowedEdges = mutableListOf<AllowedEdgeRule>()

        try {
            val yaml = Yaml()
            val root = FileInputStream(file).use { yaml.load<Map<String, Any>>(it) }

            val forbiddenRaw = root["forbiddenEdges"] as? List<Map<String, Any>> ?: emptyList()
            for ((index, entry) in forbiddenRaw.withIndex()) {
                val reason = entry["reason"]?.toString() ?: ""

                if (reason.isBlank()) {
                    errors.add("Forbidden edge rule $index: reason is blank")
                    continue
                }

                val selfEdge = entry["selfEdge"] as? Boolean
                if (selfEdge == true) {
                    forbiddenEdges.add(ForbiddenEdgeRule(selfEdge = true, reason = reason))
                    continue
                }

                forbiddenEdges.add(
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
                    errors.add("Allowed edge rule $index: reason is blank")
                    continue
                }
                allowedEdges.add(
                    AllowedEdgeRule(
                        fromLayer = entry["fromLayer"]?.toString(),
                        toLayer = entry["toLayer"]?.toString(),
                        reason = reason
                    )
                )
            }

        } catch (e: Exception) {
            errors.add("Failed to parse boundary rules: ${e.message}")
        }

        return BoundaryResult(forbiddenEdges, allowedEdges, errors)
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

        for (rule in parsedForbiddenEdges) {
            if (rule.selfEdge == true) continue // handled above

            val layerMatch = rule.fromLayer == null || (rule.fromLayer == fromLayer)
            val toLayerMatch = rule.toLayer == null || (rule.toLayer == toLayer) || rule.toLayer == "*"
            val publishedMatch = rule.fromPublished == null || (rule.fromPublished == fromPublished)
            val pubMatch = rule.toPublishability == null || (rule.toPublishability == toPublishability)

            // Check allowed edge exceptions first
            val isAllowed = parsedAllowedEdges.any { allowed ->
                val fromOk = allowed.fromLayer == null || allowed.fromLayer == fromLayer || allowed.fromLayer == "*"
                val toOk = allowed.toLayer == null || allowed.toLayer == toLayer || allowed.toLayer == "*"
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

    companion object {
        private var parsedForbiddenEdges: List<ForbiddenEdgeRule> = emptyList()
        private var parsedAllowedEdges: List<AllowedEdgeRule> = emptyList()

        fun loadOnce(boundaries: ModuleBoundaries) {
            val result = boundaries.parse()
            parsedForbiddenEdges = result.forbiddenEdges
            parsedAllowedEdges = result.allowedEdges
        }
    }
}
