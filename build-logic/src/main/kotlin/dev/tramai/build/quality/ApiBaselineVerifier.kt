package dev.tramai.build.quality

import java.io.File
import java.nio.file.Path

/** Contract-level validation and drift reporting for public API dump records. */
class ApiBaselineVerifier(
    private val repositoryRoot: File? = null,
    private val catalogModules: Map<String, ModuleCatalog.ModuleEntry> = emptyMap(),
    private val apiValidationModules: Set<String> = emptySet()
) {
    fun verify(committed: ApiBaseline, current: ApiBaseline): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        validate("Committed", committed, diagnostics)
        validate("Current", current, diagnostics)

        if (committed.aggregateHash.isNotBlank() && current.aggregateHash.isNotBlank() &&
            committed.aggregateHash != current.aggregateHash
        ) {
            diagnostics.add(
                VerificationDiagnostic.warning(
                    DiagnosticCode.API_HASH_CHANGED,
                    "Public API aggregate hash changed; run apiCheck to determine semantic compatibility"
                )
            )
        }
        return diagnostics
    }

    private fun validate(
        label: String,
        baseline: ApiBaseline,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        if (baseline.modules.isEmpty()) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.API_BASELINE_EMPTY,
                    "$label public API baseline is empty"
                )
            )
            return
        }

        baseline.modules
            .groupBy { it.dumpPath }
            .filter { (path, records) -> path.isNotBlank() && records.size > 1 }
            .toSortedMap()
            .forEach { (path, records) ->
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.API_DUMP_DUPLICATE,
                        "$label API dump '$path' is claimed by ${records.joinToString { it.module }}"
                    )
                )
            }

        sortRecords(baseline.modules).forEach { record ->
            val catalogEntry = catalogModules[record.module]
            if (record.stability !in VALID_STABILITIES || catalogEntry == null) {
                diagnostics.add(
                    VerificationDiagnostic.warning(
                        DiagnosticCode.API_MODULE_UNCLASSIFIED,
                        "$label API module '${record.module}' is missing a recognized module-catalog classification"
                    )
                )
            } else if (record.stability != catalogEntry.apiStability) {
                diagnostics.add(
                    VerificationDiagnostic.warning(
                        DiagnosticCode.API_MODULE_UNCLASSIFIED,
                        "$label API module '${record.module}' records '${record.stability}' but catalog declares " +
                            "'${catalogEntry.apiStability}'"
                    )
                )
            }

            if (!isSafeRepositoryPath(record.dumpPath)) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.API_DUMP_MISSING,
                        "$label API dump path for '${record.module}' escapes the repository: '${record.dumpPath}'",
                        modulePath = record.module
                    )
                )
            }

            if (record.applicable && (record.dumpPath.isBlank() || record.sha256.isBlank())) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        code = DiagnosticCode.API_DUMP_MISSING,
                        message = "$label applicable API module '${record.module}' has no captured dump",
                        modulePath = record.module
                    )
                )
            }

            if (record.applicable && record.stability == "stable" &&
                record.module !in apiValidationModules
            ) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.API_VALIDATION_NOT_CONFIGURED,
                        "Stable module '${record.module}' has no API validation configured",
                        modulePath = record.module
                    )
                )
            }

            validateDumpContent(label, record, diagnostics)
        }
    }

    private fun validateDumpContent(
        label: String,
        record: ApiDumpRecord,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        val root = repositoryRoot ?: return
        if (!record.applicable || !isSafeRepositoryPath(record.dumpPath)) return
        val dump = File(root, record.dumpPath)
        if (!dump.isFile) return
        val content = dump.readText(Charsets.UTF_8)
        val forbidden = listOf(root.absolutePath, System.getProperty("user.home"), "/.gradle/caches/")
            .filter { it.isNotBlank() }
        if (forbidden.any(content::contains) || ISO_TIMESTAMP.containsMatchIn(content)) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.API_DUMP_NONDETERMINISTIC,
                    "$label API dump '${record.dumpPath}' contains nondeterministic workspace data",
                    modulePath = record.module
                )
            )
        }
    }

    private fun isSafeRepositoryPath(value: String): Boolean {
        if (value.isBlank() || File(value).isAbsolute) return false
        val normalized = try {
            Path.of(value).normalize()
        } catch (_: Exception) {
            return false
        }
        return !normalized.startsWith("..")
    }

    companion object {
        private val VALID_STABILITIES = setOf("stable", "preview", "internal", "excluded")
        private val ISO_TIMESTAMP = Regex("""\b\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""")

        fun sortRecords(records: List<ApiDumpRecord>): List<ApiDumpRecord> =
            records.sortedWith(compareBy<ApiDumpRecord> { it.module }.thenBy { it.dumpPath })

        fun deterministicJson(records: List<ApiDumpRecord>): String =
            ReportNormalizer.toJson(sortRecords(records))
    }
}
