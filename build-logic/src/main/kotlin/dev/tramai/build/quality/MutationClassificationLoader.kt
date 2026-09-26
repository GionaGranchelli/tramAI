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
    val targetPhase: String? = null,
)

data class MutationClassifications(
    val schemaVersion: String,
    val classifications: List<MutationClassification>,
) {
    fun byIdentity(): Map<String, MutationClassification> = classifications.associateBy { it.id }
}

object MutationClassificationLoader {
    private val ALLOWED_CLASSIFICATIONS =
        setOf(
            "missing-test",
            "equivalent-mutant",
            "low-risk-implementation-detail",
            "tool-limitation",
            "known-design-ambiguity",
        )

    fun load(repositoryRoot: File): MutationClassifications {
        val file = File(repositoryRoot, "config/quality/mutation-classifications.yml")
        if (!file.isFile) {
            return MutationClassifications(schemaVersion = "1", classifications = emptyList())
        }
        val loaderOptions =
            LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 20
            }
        val raw =
            try {
                @Suppress("UNCHECKED_CAST")
                Yaml(loaderOptions).load<Map<String, Any?>>(file.readText(Charsets.UTF_8))
                    ?: emptyMap()
            } catch (e: Exception) {
                throw GradleException("Invalid mutation-classifications.yml: ${e.message}", e)
            }

        val schemaVersion =
            raw["schemaVersion"]?.toString()
                ?: throw GradleException("mutation-classifications.yml: schemaVersion is required")
        if (schemaVersion != "1") {
            throw GradleException("Unsupported mutation-classifications.yml schemaVersion '$schemaVersion'")
        }

        val classificationsRaw =
            raw["classifications"] as? List<*>
                ?: throw GradleException("mutation-classifications.yml: classifications must be a list")

        val classifications =
            classificationsRaw.mapIndexed { index, item ->
                parseClassificationEntry(
                    entry = item,
                    index = index,
                    fileName = "mutation-classifications.yml",
                    collectionName = "classifications",
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

    /**
     * Parse and validate one classification record.
     *
     * Shared with [MutationClassificationEnrollmentLoader] on purpose: the
     * allowed-classification vocabulary and the field rules are one authority,
     * so an enrollment can never accept a classification the classifications
     * file would reject (or vice versa).
     */
    internal fun parseClassificationEntry(
        entry: Any?,
        index: Int,
        fileName: String,
        collectionName: String,
    ): MutationClassification {
        val mapping =
            entry as? Map<*, *>
                ?: throw GradleException("$fileName: $collectionName[$index] must be a mapping")
        val where = "$collectionName[$index]"
        val classification = requiredField(mapping, "classification", fileName, where)
        requireAllowedClassification(classification, fileName, where)
        return MutationClassification(
            id = requiredField(mapping, "id", fileName, where),
            classification = classification,
            reason = requiredField(mapping, "reason", fileName, where),
            issue = optionalField(mapping, "issue"),
            targetPhase = optionalField(mapping, "targetPhase"),
        )
    }

    private fun requiredField(
        mapping: Map<*, *>,
        field: String,
        fileName: String,
        where: String,
    ): String {
        val value = (mapping[field] as? String)?.trim().orEmpty()
        if (value.isEmpty()) {
            throw GradleException("$fileName: $where.$field must not be empty")
        }
        return value
    }

    private fun optionalField(
        mapping: Map<*, *>,
        field: String,
    ): String? = (mapping[field] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun requireAllowedClassification(
        classification: String,
        fileName: String,
        where: String,
    ) {
        if (classification !in ALLOWED_CLASSIFICATIONS) {
            throw GradleException(
                "$fileName: $where.classification '$classification' is not allowed. " +
                    "Allowed values: ${ALLOWED_CLASSIFICATIONS.joinToString()}",
            )
        }
    }
}
