package dev.tramai.build.quality

import java.io.File

/** Validates resolved external dependencies and reports deterministic baseline drift. */
class DependencyBaselineVerifier {
    fun verify(
        committed: List<ResolvedDependency>,
        current: List<ResolvedDependency>,
        resolutionFailure: Throwable? = null
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        if (committed.isEmpty()) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.DEPENDENCY_BASELINE_EMPTY,
                    "Committed resolved dependency baseline is empty"
                )
            )
        }
        if (resolutionFailure != null) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED,
                    "Current dependency resolution failed: ${resolutionFailure.message ?: resolutionFailure.javaClass.name}"
                )
            )
            return diagnostics
        }
        if (current.isEmpty()) {
            diagnostics.add(
                VerificationDiagnostic.failure(
                    DiagnosticCode.DEPENDENCY_BASELINE_EMPTY,
                    "Current resolved dependency baseline is empty"
                )
            )
        }

        sortRecords(current).forEach { dependency ->
            val requested = dependency.requestedVersion.orEmpty()
            if (isDynamicVersion(requested)) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.DYNAMIC_DEPENDENCY_VERSION,
                        "${coordinate(dependency)} uses dynamic selector '$requested'"
                    )
                )
            }
            if (dependency.selectedVersion.endsWith("-SNAPSHOT", ignoreCase = true)) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.SNAPSHOT_DEPENDENCY,
                        "${coordinate(dependency)} resolves to a SNAPSHOT version"
                    )
                )
            }
            if (dependency.consumers.isEmpty() || dependency.consumers.any { it.isBlank() } ||
                dependency.configuration.isBlank()
            ) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED,
                        "${coordinate(dependency)} lacks consumer or configuration information"
                    )
                )
            }
            if (dependency.dependencyPath.any(::containsAbsolutePath)) {
                diagnostics.add(
                    VerificationDiagnostic.failure(
                        DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED,
                        "${coordinate(dependency)} contains an absolute filesystem path"
                    )
                )
            }
        }

        // Determine pre-existing convergence issues from committed baseline
        val committedVersions = committed.groupBy { "${it.group}:${it.artifact}" }
            .mapValues { (_, records) ->
                records.map { it.selectedVersion }.toSortedSet()
            }

        // Check current for NEW convergence issues
        current.groupBy { "${it.group}:${it.artifact}" }
            .toSortedMap()
            .forEach { (coordinate, records) ->
                val currentVersions = records.map { it.selectedVersion }.toSortedSet()
                if (currentVersions.size > 1) {
                    val previousVersions = committedVersions[coordinate].orEmpty()
                    if (currentVersions != previousVersions) {
                        diagnostics.add(
                            VerificationDiagnostic.failure(
                                DiagnosticCode.DEPENDENCY_CONVERGENCE_FAILURE,
                                "$coordinate resolves to multiple production versions: ${currentVersions.joinToString()}" +
                                    if (previousVersions.isNotEmpty()) " (was ${previousVersions.joinToString()})" else ""
                            )
                        )
                    }
                }
            }

        addDriftDiagnostics(committed, current, diagnostics)
        return diagnostics
    }

    private fun addDriftDiagnostics(
        committed: List<ResolvedDependency>,
        current: List<ResolvedDependency>,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        val oldByIdentity = committed.groupBy(::versionlessIdentity)
        val newByIdentity = current.groupBy(::versionlessIdentity)
        (newByIdentity.keys - oldByIdentity.keys).sorted().forEach { identity ->
            diagnostics.add(
                VerificationDiagnostic.warning(
                    DiagnosticCode.DEPENDENCY_ADDED,
                    "Resolved dependency added: $identity"
                )
            )
        }
        (oldByIdentity.keys - newByIdentity.keys).sorted().forEach { identity ->
            diagnostics.add(
                VerificationDiagnostic.warning(
                    DiagnosticCode.DEPENDENCY_REMOVED,
                    "Resolved dependency removed: $identity"
                )
            )
        }
        (oldByIdentity.keys intersect newByIdentity.keys).sorted().forEach { identity ->
            val oldVersions = oldByIdentity.getValue(identity).map { it.selectedVersion }.toSortedSet()
            val newVersions = newByIdentity.getValue(identity).map { it.selectedVersion }.toSortedSet()
            if (oldVersions != newVersions) {
                diagnostics.add(
                    VerificationDiagnostic.warning(
                        DiagnosticCode.DEPENDENCY_VERSION_CHANGED,
                        "$identity changed version from ${oldVersions.joinToString()} to ${newVersions.joinToString()}"
                    )
                )
            }
        }
    }

    private fun versionlessIdentity(dependency: ResolvedDependency): String {
        val parent = dependency.dependencyPath
            .dropLast(1)
            .lastOrNull()
            .orEmpty()

        return listOf(
            dependency.consumers.joinToString(","),
            dependency.configuration,
            stripVersion(parent),
            dependency.group,
            dependency.artifact,
            dependency.requestedVersion.orEmpty(),
            dependency.direct.toString()
        ).joinToString("|")
    }

    private fun stripVersion(pathElement: String): String {
        val parts = pathElement.split(":")
        return if (parts.size >= 3 && !pathElement.startsWith(":")) {
            parts.dropLast(1).joinToString(":")
        } else {
            pathElement
        }
    }

    private fun coordinate(dependency: ResolvedDependency): String =
        "${dependency.group}:${dependency.artifact}:${dependency.selectedVersion}"

    private fun isDynamicVersion(version: String): Boolean =
        version.contains('+') ||
            version.startsWith("latest.", ignoreCase = true) ||
            version.startsWith("[") || version.startsWith("(")

    private fun containsAbsolutePath(value: String): Boolean =
        File(value).isAbsolute || WINDOWS_ABSOLUTE.containsMatchIn(value) ||
            value.contains("/.gradle/caches/") || value.contains("\\.gradle\\caches\\")

    companion object {
        private val WINDOWS_ABSOLUTE = Regex("""^[A-Za-z]:[\\/]""")

        fun sortRecords(records: List<ResolvedDependency>): List<ResolvedDependency> =
            BaselineGenerator.sortResolvedDependencies(records)

        fun deterministicJson(records: List<ResolvedDependency>): String =
            ReportNormalizer.toJson(sortRecords(records))
    }
}
