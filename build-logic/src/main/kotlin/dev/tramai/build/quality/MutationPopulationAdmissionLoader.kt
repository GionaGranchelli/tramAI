package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.File

/**
 * Parses config/quality/mutation-population-admissions.yml.
 *
 * An absent file means "no authorizations", which is fail-closed: population admission is then
 * impossible and every appearing candidate-only NON_KILLED identity still fails M06. A present but
 * malformed file is a hard failure - a broken ledger must never silently degrade into "no
 * authorizations that happen to be unused".
 *
 * Every enforcement-critical field is required and validated here, because a partially specified
 * authorization is indistinguishable from an authorization that authorizes a different row.
 */
object MutationPopulationAdmissionLoader {
    const val FILE_NAME = "config/quality/mutation-population-admissions.yml"

    private const val SCHEMA_VERSION = "1"
    private const val DEFAULT_TIMEOUT_FACTOR = 1.25
    private const val MAX_ALIASES_FOR_COLLECTIONS = 20
    private val IDENTITY = Regex("[0-9a-f]{64}")
    private val COMMIT_SHA = Regex("[0-9a-f]{40}")

    fun load(repositoryRoot: File): MutationPopulationAdmissions {
        val file = File(repositoryRoot, FILE_NAME)
        if (!file.isFile) {
            return MutationPopulationAdmissions.NONE
        }
        val raw = readLedger(file)
        val schemaVersion = requireSchemaVersion(raw)
        val admissions = parseAdmissions(raw)
        requireUniqueIdentities(admissions)
        return MutationPopulationAdmissions(schemaVersion = schemaVersion, admissions = admissions)
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
            throw GradleException("Invalid mutation-population-admissions.yml: ${e.message}", e)
        }
    }

    private fun requireSchemaVersion(raw: Map<String, Any?>): String {
        val schemaVersion =
            raw["schemaVersion"]?.toString()
                ?: throw GradleException("mutation-population-admissions.yml: schemaVersion is required")
        if (schemaVersion != SCHEMA_VERSION) {
            throw GradleException(
                "Unsupported mutation-population-admissions.yml schemaVersion '$schemaVersion'",
            )
        }
        return schemaVersion
    }

    private fun parseAdmissions(raw: Map<String, Any?>): List<MutationPopulationAdmission> {
        val admissionsRaw =
            raw["admissions"] as? List<*>
                ?: throw GradleException("mutation-population-admissions.yml: admissions must be a list")
        return admissionsRaw.mapIndexed { index, item ->
            val entry =
                item as? Map<*, *>
                    ?: throw GradleException("mutation-population-admissions.yml: admissions[$index] must be a mapping")
            val identity =
                requireCanonical(entry, "identity", index, IDENTITY, "a canonical 64-hex schema v2 identity")
            val fromBaseSha = requireCanonical(entry, "fromBaseSha", index, COMMIT_SHA, "a 40-hex commit SHA")
            val populationDigest =
                requireCanonical(entry, "populationDigest", index, IDENTITY, "a 64-hex projection hash")
            MutationPopulationAdmission(
                identity = identity,
                status = requireString(entry, "status", index),
                outcome = requireString(entry, "outcome", index),
                family = requireString(entry, "family", index),
                module = requireString(entry, "module", index),
                analyzer = parseAnalyzer(entry, index),
                fromBaseSha = fromBaseSha,
                populationDigest = populationDigest,
                reason = requireString(entry, "reason", index),
                issue = optionalString(entry, "issue"),
                targetPhase = optionalString(entry, "targetPhase"),
                authorizedBy = optionalString(entry, "authorizedBy"),
                authorizedAt = optionalString(entry, "authorizedAt"),
            )
        }
    }

    /** Analyzer semantics are part of the authorized meaning (M16-M19); a partial one is invalid. */
    private fun parseAnalyzer(
        entry: Map<*, *>,
        index: Int,
    ): MutationAnalyzerSemantics {
        val analyzer =
            entry["analyzer"] as? Map<*, *>
                ?: throw GradleException(
                    "mutation-population-admissions.yml: admissions[$index].analyzer is required",
                )
        val pluginVersion = requiredAnalyzerString(analyzer, "pluginVersion", index)
        val engineVersion = requiredAnalyzerString(analyzer, "engineVersion", index)
        val mutators =
            (analyzer["mutators"] as? List<*>)?.map { it?.toString() ?: "" }?.filter { it.isNotBlank() } ?: emptyList()
        require(mutators.isNotEmpty()) {
            "mutation-population-admissions.yml: admissions[$index].analyzer.mutators must be a non-empty list (M19)"
        }
        val timeoutConst = (analyzer["timeoutConst"] as? Number)?.toInt() ?: 0
        require(timeoutConst > 0) {
            "mutation-population-admissions.yml: admissions[$index].analyzer.timeoutConst must be positive (C1)"
        }
        val timeoutFactor = (analyzer["timeoutFactor"] as? Number)?.toDouble() ?: DEFAULT_TIMEOUT_FACTOR
        return MutationAnalyzerSemantics(
            pluginVersion = pluginVersion,
            engineVersion = engineVersion,
            mutators = mutators.sorted(),
            timeoutConst = timeoutConst,
            timeoutFactor = timeoutFactor,
        )
    }

    private fun requiredAnalyzerString(
        analyzer: Map<*, *>,
        field: String,
        index: Int,
    ): String =
        analyzer[field]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "mutation-population-admissions.yml: admissions[$index].analyzer.$field is required (M19)",
            )

    /**
     * Structural form only. The loader deliberately never decides whether a digest corresponds to a
     * real campaign - that judgement belongs to the verifier at consumption, against its own
     * measurement - so an incorrect digest at mint grants nothing.
     */
    private fun requireCanonical(
        entry: Map<*, *>,
        field: String,
        index: Int,
        pattern: Regex,
        expected: String,
    ): String {
        val value = requireString(entry, field, index)
        require(pattern.matches(value)) {
            "mutation-population-admissions.yml: admissions[$index].$field '$value' is not $expected"
        }
        return value
    }

    private fun requireString(
        entry: Map<*, *>,
        field: String,
        index: Int,
    ): String =
        entry[field]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "mutation-population-admissions.yml: admissions[$index].$field is required and must be non-blank",
            )

    private fun optionalString(
        entry: Map<*, *>,
        field: String,
    ): String? = entry[field]?.toString()?.takeIf { it.isNotBlank() }

    private fun requireUniqueIdentities(admissions: List<MutationPopulationAdmission>) {
        val seen = mutableSetOf<String>()
        admissions.forEach { admission ->
            if (!seen.add(admission.identity)) {
                throw GradleException(
                    "mutation-population-admissions.yml: duplicate admission identity '${admission.identity}' - " +
                        "an identity may be authorized once.",
                )
            }
        }
    }
}
