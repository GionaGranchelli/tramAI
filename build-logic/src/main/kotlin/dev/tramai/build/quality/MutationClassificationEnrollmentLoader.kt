package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.File

/**
 * Parses config/quality/mutation-classification-enrollments.yml.
 *
 * An absent file means "no authorizations", which is fail-closed: enrollment is
 * then impossible and every candidate-side classification addition still fails
 * M08/M09. A present but malformed file is a hard failure — a broken ledger must
 * never silently degrade into "no authorizations that happen to be unused".
 *
 * Classification vocabulary and per-entry validation are shared with
 * [MutationClassificationLoader] so the two authorities cannot drift.
 */
object MutationClassificationEnrollmentLoader {
    const val FILE_NAME = "config/quality/mutation-classification-enrollments.yml"

    private const val SCHEMA_VERSION = "1"
    private const val MAX_ALIASES_FOR_COLLECTIONS = 20

    fun load(repositoryRoot: File): MutationClassificationEnrollments {
        val file = File(repositoryRoot, FILE_NAME)
        if (!file.isFile) {
            return MutationClassificationEnrollments.NONE
        }
        val raw = readLedger(file)
        val schemaVersion = requireSchemaVersion(raw)
        val enrollments = parseEnrollments(raw)
        requireUniqueIds(enrollments)
        return MutationClassificationEnrollments(schemaVersion = schemaVersion, enrollments = enrollments)
    }

    private fun readLedger(file: File): Map<String, Any?> {
        val loaderOptions =
            LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = MAX_ALIASES_FOR_COLLECTIONS
            }
        val text = file.readText(Charsets.UTF_8)
        return try {
            @Suppress("UNCHECKED_CAST")
            Yaml(loaderOptions).load<Map<String, Any?>>(text) ?: emptyMap()
        } catch (e: YAMLException) {
            throw GradleException("Invalid mutation-classification-enrollments.yml: ${e.message}", e)
        }
    }

    private fun requireSchemaVersion(raw: Map<String, Any?>): String {
        val schemaVersion =
            raw["schemaVersion"]?.toString()
                ?: throw GradleException("mutation-classification-enrollments.yml: schemaVersion is required")
        if (schemaVersion != SCHEMA_VERSION) {
            throw GradleException(
                "Unsupported mutation-classification-enrollments.yml schemaVersion '$schemaVersion'",
            )
        }
        return schemaVersion
    }

    private fun parseEnrollments(raw: Map<String, Any?>): List<MutationClassificationEnrollment> {
        val enrollmentsRaw =
            raw["enrollments"] as? List<*>
                ?: throw GradleException("mutation-classification-enrollments.yml: enrollments must be a list")
        return enrollmentsRaw.mapIndexed { index, item ->
            MutationClassificationEnrollment(
                classification =
                    MutationClassificationLoader.parseClassificationEntry(
                        entry = item,
                        index = index,
                        fileName = "mutation-classification-enrollments.yml",
                        collectionName = "enrollments",
                    ),
            )
        }
    }

    private fun requireUniqueIds(enrollments: List<MutationClassificationEnrollment>) {
        val seenIds = mutableSetOf<String>()
        enrollments.forEach { enrollment ->
            val id = enrollment.classification.id
            if (!seenIds.add(id)) {
                throw GradleException(
                    "mutation-classification-enrollments.yml: duplicate enrollment id '$id' — " +
                        "an identity may be authorized once.",
                )
            }
        }
    }
}
