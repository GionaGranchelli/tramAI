package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.File

/**
 * Parses config/quality/mutation-authority-digest-certificates.yml.
 *
 * An absent file means "no certificates", which is fail-closed: no semantic migration of a
 * population-authority digest is then possible, and every authorization whose digest predates
 * authority-v2 stays unconsumable. A present but malformed file is a hard failure - a broken
 * certificate ledger must never silently degrade into "no certificates that happen to be unused".
 *
 * Every enforcement-critical field is required and validated here, because a partially specified
 * certificate is indistinguishable from one that authorizes a different migration.
 *
 * **This loader validates shape only.** Mint-time base binding (M45), retained immutability (M46)
 * and removal custody (M47) are transition properties and cannot be enforced by parsing a single
 * file; they live in [MutationAuthorityDigestCertificateCeremony]. Leaving this ledger's lifecycle
 * to a generic loader would give it none of those properties.
 */
object MutationAuthorityDigestCertificateLoader {
    const val FILE_NAME = "config/quality/mutation-authority-digest-certificates.yml"

    private const val SCHEMA_VERSION = "1"
    private const val MAX_ALIASES_FOR_COLLECTIONS = 20
    private val DIGEST = Regex("[0-9a-f]{64}")
    private val COMMIT_SHA = Regex("[0-9a-f]{40}")
    private val ALGORITHMS =
        setOf(
            MutationAuthorityDigestCertificates.ALGORITHM_RAW_V1,
            MutationAuthorityDigestCertificates.ALGORITHM_AUTHORITY_V2,
        )

    fun load(repositoryRoot: File): MutationAuthorityDigestCertificates {
        val file = File(repositoryRoot, FILE_NAME)
        if (!file.isFile) {
            return MutationAuthorityDigestCertificates.NONE
        }
        val raw = readLedger(file)
        val schemaVersion = requireSchemaVersion(raw)
        val certificates = parseCertificates(raw)
        requireUniqueFromDigests(certificates)
        requireDistinctAlgorithms(certificates)
        return MutationAuthorityDigestCertificates(
            schemaVersion = schemaVersion,
            certificates = certificates,
        )
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
            throw GradleException("Invalid mutation-authority-digest-certificates.yml: ${e.message}", e)
        }
    }

    private fun requireSchemaVersion(raw: Map<String, Any?>): String {
        val schemaVersion =
            raw["schemaVersion"]?.toString()
                ?: throw GradleException(
                    "mutation-authority-digest-certificates.yml: schemaVersion is required",
                )
        if (schemaVersion != SCHEMA_VERSION) {
            throw GradleException(
                "Unsupported mutation-authority-digest-certificates.yml schemaVersion '$schemaVersion'",
            )
        }
        return schemaVersion
    }

    private fun parseCertificates(raw: Map<String, Any?>): List<MutationAuthorityDigestCertificate> {
        val raw =
            raw["certificates"] as? List<*>
                ?: throw GradleException(
                    "mutation-authority-digest-certificates.yml: certificates must be a list",
                )
        return raw.mapIndexed { index, item ->
            val entry =
                item as? Map<*, *>
                    ?: throw GradleException(
                        "mutation-authority-digest-certificates.yml: certificates[$index] must be a mapping",
                    )
            MutationAuthorityDigestCertificate(
                fromAlgorithm = requireAlgorithm(entry, "fromAlgorithm", index),
                fromDigest = requireCanonical(entry, "fromDigest", index, DIGEST, "a 64-hex digest"),
                toAlgorithm = requireAlgorithm(entry, "toAlgorithm", index),
                toDigest = requireCanonical(entry, "toDigest", index, DIGEST, "a 64-hex digest"),
                admissionSetDigest =
                    requireCanonical(
                        entry,
                        "admissionSetDigest",
                        index,
                        DIGEST,
                        "a 64-hex admission-set digest",
                    ),
                fromBaseSha =
                    requireCanonical(entry, "fromBaseSha", index, COMMIT_SHA, "a 40-hex commit SHA"),
                reason = requireString(entry, "reason", index),
                authorizedBy = optionalString(entry, "authorizedBy"),
                authorizedAt = optionalString(entry, "authorizedAt"),
            )
        }
    }

    private fun requireAlgorithm(
        entry: Map<*, *>,
        field: String,
        index: Int,
    ): String {
        val value =
            entry[field]?.toString()
                ?: throw GradleException(
                    "mutation-authority-digest-certificates.yml: certificates[$index].$field is required",
                )
        if (value !in ALGORITHMS) {
            throw GradleException(
                "mutation-authority-digest-certificates.yml: certificates[$index].$field '$value' is " +
                    "not a known digest semantics (${ALGORITHMS.sorted().joinToString(", ")})",
            )
        }
        return value
    }

    private fun requireCanonical(
        entry: Map<*, *>,
        field: String,
        index: Int,
        pattern: Regex,
        description: String,
    ): String {
        val value =
            entry[field]?.toString()
                ?: throw GradleException(
                    "mutation-authority-digest-certificates.yml: certificates[$index].$field is required",
                )
        if (!pattern.matches(value)) {
            throw GradleException(
                "mutation-authority-digest-certificates.yml: certificates[$index].$field '$value' is not " +
                    "$description",
            )
        }
        return value
    }

    private fun requireString(
        entry: Map<*, *>,
        field: String,
        index: Int,
    ): String {
        val value = entry[field]?.toString()?.takeIf { it.isNotBlank() }
        return value
            ?: throw GradleException(
                "mutation-authority-digest-certificates.yml: certificates[$index].$field is required and " +
                    "must be non-blank",
            )
    }

    private fun optionalString(
        entry: Map<*, *>,
        field: String,
    ): String? = entry[field]?.toString()?.takeIf { it.isNotBlank() }

    private fun requireUniqueFromDigests(certificates: List<MutationAuthorityDigestCertificate>) {
        val duplicates =
            certificates
                .groupingBy { it.fromDigest }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        if (duplicates.isNotEmpty()) {
            throw GradleException(
                "mutation-authority-digest-certificates.yml: duplicate fromDigest ${duplicates.sorted()} - " +
                    "the source of a migration is unique, so two certificates claiming it would contradict " +
                    "each other",
            )
        }
    }

    private fun requireDistinctAlgorithms(certificates: List<MutationAuthorityDigestCertificate>) {
        for (certificate in certificates) {
            if (certificate.fromAlgorithm == certificate.toAlgorithm) {
                throw GradleException(
                    "mutation-authority-digest-certificates.yml: certificate ${certificate.fromDigest} " +
                        "migrates ${certificate.fromAlgorithm} to itself, which certifies nothing",
                )
            }
        }
    }
}
