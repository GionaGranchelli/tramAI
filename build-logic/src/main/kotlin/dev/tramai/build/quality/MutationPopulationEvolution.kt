package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.File
import java.io.IOException

/** Who is allowed to consider a base mutation identity that is absent from the candidate. */
enum class MutationPopulationEvolution {
    /** Absolute default: any base-only identity fails (M21). */
    FORBID,

    /** Requires invocation authority and a per-identity audit record. */
    RECORDED_EVOLUTION,
    ;

    companion object {
        const val PROPERTY = "tramaiMutationEvolution"

        /** Absent -> FORBID. Unknown non-blank value -> throw. */
        fun fromProperty(raw: String?): MutationPopulationEvolution =
            when (raw?.trim().orEmpty()) {
                "" -> FORBID
                "forbid" -> FORBID
                "recorded-evolution" -> RECORDED_EVOLUTION
                else -> throw GradleException("Unknown $PROPERTY value '$raw'")
            }
    }
}

/** One reviewed population removal: config/quality/mutation-evolution.yml. */
data class MutationEvolutionRecord(
    val id: String,
    val fromBaseSha: String,
    val reason: String,
    val issue: String? = null,
    val targetPhase: String? = null,
    val authorizedAt: String? = null,
    val authorizedBy: String? = null,
)

data class MutationEvolutionRecords(
    val schemaVersion: String,
    val records: List<MutationEvolutionRecord>,
) {
    fun byIdentity(): Map<String, MutationEvolutionRecord> = records.associateBy { it.id }
}

object MutationEvolutionLoader {
    private const val MAX_ALIASES = 20

    fun load(repositoryRoot: File): MutationEvolutionRecords {
        val file = File(repositoryRoot, "config/quality/mutation-evolution.yml")
        if (!file.isFile) return MutationEvolutionRecords("1", emptyList())
        val raw = parse(file)
        val schemaVersion = schemaVersion(raw)
        val records = records(raw)
        val parsed = records.mapIndexed(::parseRecord)
        ensureUnique(parsed)
        return MutationEvolutionRecords(schemaVersion, parsed)
    }

    private fun parse(file: File): Map<*, *> {
        val loaded =
            try {
                val options =
                    LoaderOptions().apply {
                        isAllowDuplicateKeys = false
                        maxAliasesForCollections = MAX_ALIASES
                    }
                Yaml(options).load<Any?>(file.readText(Charsets.UTF_8)) ?: emptyMap<Any?, Any?>()
            } catch (e: IOException) {
                throw GradleException("Invalid mutation-evolution.yml: ${e.message}", e)
            } catch (e: YAMLException) {
                throw GradleException("Invalid mutation-evolution.yml: ${e.message}", e)
            }
        return requireMapping(loaded)
    }

    private fun requireMapping(value: Any?): Map<*, *> =
        value as? Map<*, *> ?: throw GradleException("mutation-evolution.yml must contain a mapping")

    private fun schemaVersion(raw: Map<*, *>): String {
        val value =
            raw["schemaVersion"]?.toString()
                ?: throw GradleException("mutation-evolution.yml: schemaVersion is required")
        if (value != "1") throw GradleException("Unsupported mutation-evolution.yml schemaVersion '$value'")
        return value
    }

    private fun records(raw: Map<*, *>): List<*> =
        raw["records"] as? List<*>
            ?: throw GradleException("mutation-evolution.yml: records must be a list")

    private fun ensureUnique(records: List<MutationEvolutionRecord>) {
        val ids = records.map { it.id }
        if (ids.size != ids.distinct().size) {
            throw GradleException("mutation-evolution.yml: duplicate evolution record id")
        }
    }

    private fun parseRecord(
        index: Int,
        item: Any?,
    ): MutationEvolutionRecord {
        val entry =
            item as? Map<*, *>
                ?: throw GradleException("mutation-evolution.yml: records[$index] must be a mapping")
        return MutationEvolutionRecord(
            id = required(entry, "id", index),
            fromBaseSha = required(entry, "fromBaseSha", index),
            reason = required(entry, "reason", index),
            issue = optional(entry, "issue"),
            targetPhase = optional(entry, "targetPhase"),
            authorizedAt = optional(entry, "authorizedAt"),
            authorizedBy = optional(entry, "authorizedBy"),
        )
    }

    private fun required(
        entry: Map<*, *>,
        name: String,
        index: Int,
    ): String =
        (entry[name] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("mutation-evolution.yml: records[$index].$name must not be empty")

    private fun optional(
        entry: Map<*, *>,
        name: String,
    ): String? = (entry[name] as? String)?.trim()?.takeIf { it.isNotEmpty() }
}
