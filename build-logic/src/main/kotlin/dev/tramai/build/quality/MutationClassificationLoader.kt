package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Parses config/quality/mutation-classifications.yml and validates classifications.
 */
data class MutationClassification(
    val id: String,
    val classification: String,
    val reason: String,
    val issue: String? = null,
    val targetPhase: String? = null
)

data class MutationClassifications(
    val schemaVersion: String,
    val classifications: List<MutationClassification>
) {
    fun byIdentity(): Map<String, MutationClassification> =
        classifications.associateBy { it.id }
}

object MutationClassificationLoader {
    private val ALLOWED_CLASSIFICATIONS = setOf(
        "missing-test",
        "equivalent-mutant",
        "low-risk-implementation-detail",
        "tool-limitation",
        "known-design-ambiguity"
    )

    fun load(repositoryRoot: File): MutationClassifications {
        val file = File(repositoryRoot, "config/quality/mutation-classifications.yml")
        if (!file.isFile) {
            return MutationClassifications(schemaVersion = "1", classifications = emptyList())
        }
        val loaderOptions = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 20
        }
        val raw = try {
            @Suppress("UNCHECKED_CAST")
            Yaml(loaderOptions).load<Map<String, Any?>>(file.readText(Charsets.UTF_8))
                ?: emptyMap()
        } catch (e: Exception) {
            throw GradleException("Invalid mutation-classifications.yml: ${e.message}", e)
        }

        val schemaVersion = raw["schemaVersion"]?.toString()
            ?: throw GradleException("mutation-classifications.yml: schemaVersion is required")
        if (schemaVersion != "1") {
            throw GradleException("Unsupported mutation-classifications.yml schemaVersion '$schemaVersion'")
        }

        val classificationsRaw = raw["classifications"] as? List<*>
            ?: throw GradleException("mutation-classifications.yml: classifications must be a list")

        val classifications = classificationsRaw.mapIndexed { index, item ->
            val entry = item as? Map<*, *>
                ?: throw GradleException("mutation-classifications.yml: classifications[$index] must be a mapping")
            val id = (entry["id"] as? String)?.trim().orEmpty()
            val classification = (entry["classification"] as? String)?.trim().orEmpty()
            val reason = (entry["reason"] as? String)?.trim().orEmpty()
            val issue = (entry["issue"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val targetPhase = (entry["targetPhase"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

            if (id.isEmpty()) {
                throw GradleException("mutation-classifications.yml: classifications[$index].id must not be empty")
            }
            if (classification.isEmpty()) {
                throw GradleException("mutation-classifications.yml: classifications[$index].classification must not be empty")
            }
            if (classification !in ALLOWED_CLASSIFICATIONS) {
                throw GradleException(
                    "mutation-classifications.yml: classifications[$index].classification '$classification' is not allowed. " +
                        "Allowed values: ${ALLOWED_CLASSIFICATIONS.joinToString()}"
                )
            }
            if (reason.isEmpty()) {
                throw GradleException("mutation-classifications.yml: classifications[$index].reason must not be empty")
            }
            MutationClassification(
                id = id,
                classification = classification,
                reason = reason,
                issue = issue,
                targetPhase = targetPhase
            )
        }

        val seenIds = mutableSetOf<String>()
        classifications.forEach { c ->
            if (c.id in seenIds) {
                throw GradleException("mutation-classifications.yml: duplicate classification id '${c.id}'")
            }
            seenIds.add(c.id)
        }

        return MutationClassifications(schemaVersion = schemaVersion, classifications = classifications)
    }
}
