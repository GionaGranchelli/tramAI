package dev.tramai.build.quality

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream
import java.time.LocalDate

/**
 * Parses and validates maintainability deviations from config/quality/maintainability-deviations.yml.
 */
class DeviationParser(private val rootDir: File) {

    data class DeviationEntry(
        val id: String,
        val metric: String,
        val scope: String,
        val baseline: Int,
        val allowed: Int,
        val reason: String,
        val acceptedAt: String,
        val targetPhase: String,
        val owner: String
    )

    data class ParseResult(
        val deviations: List<DeviationEntry>,
        val errors: List<String>
    )

    fun parse(): ParseResult {
        val file = File(rootDir, "config/quality/maintainability-deviations.yml")
        if (!file.isFile) {
            return ParseResult(emptyList(), listOf("Deviation file not found: ${file.absolutePath}"))
        }

        val errors = mutableListOf<String>()
        val deviations = mutableListOf<DeviationEntry>()

        try {
            val yaml = Yaml()
            val root = FileInputStream(file).use { yaml.load<Map<String, Any>>(it) }
            val entries = root["deviations"] as? List<Map<String, Any>> ?: emptyList()

            for ((index, entry) in entries.withIndex()) {
                val id = entry["id"]?.toString() ?: "MQ-${index}"
                val metric = entry["metric"]?.toString() ?: ""
                val scope = entry["scope"]?.toString() ?: ""
                val baseline = (entry["baseline"] as? Number)?.toInt() ?: 0
                val allowed = (entry["allowed"] as? Number)?.toInt() ?: 0
                val reason = entry["reason"]?.toString() ?: ""
                val acceptedAt = entry["acceptedAt"]?.toString() ?: ""
                val targetPhase = entry["targetPhase"]?.toString() ?: ""
                val owner = entry["owner"]?.toString() ?: ""

                // Validate
                if (metric.isBlank()) {
                    errors.add("$id: metric is blank")
                }
                if (baseline == 0 && allowed == 0 && !reason.contains("placeholder", ignoreCase = true)) {
                    // Both zero without explanation — suspicious
                    errors.add("$id: baseline and allowed are both 0 without placeholder justification")
                }
                if (reason.isBlank()) {
                    errors.add("$id: reason is blank")
                }
                if (targetPhase.isBlank()) {
                    errors.add("$id: targetPhase is blank")
                }

                deviations.add(
                    DeviationEntry(id, metric, scope, baseline, allowed, reason, acceptedAt, targetPhase, owner)
                )
            }

            checkDuplicateIds(deviations, errors)
            checkExpiredDeviations(deviations, errors)

        } catch (e: Exception) {
            errors.add("Failed to parse deviation file: ${e.message}")
        }

        return ParseResult(deviations, errors)
    }

    private fun checkDuplicateIds(deviations: List<DeviationEntry>, errors: MutableList<String>) {
        val duplicates = deviations.groupBy { it.id }.filter { it.value.size > 1 }
        for ((id, entries) in duplicates) {
            errors.add("Duplicate deviation ID: $id (${entries.size} entries)")
        }
    }

    private fun checkExpiredDeviations(deviations: List<DeviationEntry>, errors: MutableList<String>) {
        // Deviations with target phase ≤ 0.6.0 (the current release) are expired
        val expiredPrefixes = listOf("0.6.0", "0.5", "0.4", "0.3", "0.2", "0.1")
        for (dev in deviations) {
            if (expiredPrefixes.any { dev.targetPhase.startsWith(it) }) {
                errors.add("${dev.id}: targetPhase ${dev.targetPhase} has expired — deviation should be resolved or its phase updated")
            }
        }
    }

    /**
     * Find a deviation that covers a given finding. Returns the deviation if one matches.
     */
    fun findCoveringDeviation(deviations: List<DeviationEntry>, metric: String, scope: String, currentValue: Int): DeviationEntry? {
        return deviations.find { dev ->
            dev.metric == metric &&
                (dev.scope == scope || scope.startsWith(dev.scope) || dev.scope == "*") &&
                currentValue <= dev.allowed
        }
    }
}
