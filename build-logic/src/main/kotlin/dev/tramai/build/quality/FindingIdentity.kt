package dev.tramai.build.quality

/**
 * Category of a maintainability finding.
 */
enum class FindingCategory {
    CANCELLATION_CATCH,
    GLOBAL_STATE,
    NONDETERMINISM,
    PROTOCOL_IDENTIFIER,
    STRUCTURAL_HOTSPOT,
    DEPENDENCY_EDGE,
    DEPENDENCY_CYCLE
}

/**
 * Stable finding identity that does not depend primarily on line numbers.
 *
 * Rules:
 * - repositoryPath is the normalized project-relative file path.
 * - declaration is the enclosing type/function where available.
 * - discriminator is used for ambiguous cases (e.g. catchType, kind).
 * - occurrence is a stable ordinal for multiple identical findings
 *   inside the same declaration, ordered by source position.
 * - Line numbers may be displayed but must NOT be part of the identity.
 */
data class FindingIdentity(
    val category: FindingCategory,
    val modulePath: String,
    val repositoryPath: String,
    val declaration: String? = null,
    val discriminator: String? = null,
    val occurrence: Int? = null
) {
    /**
     * Compact string key for identity comparison (line-number independent).
     * Nullable fields are omitted rather than serialized as "null".
     * Occurrence is included when present so duplicates in the same
     * declaration have unique identities.
     */
    fun toIdentityKey(): String {
        val parts = listOfNotNull(
            category.name,
            modulePath,
            repositoryPath,
            declaration,
            discriminator,
            occurrence?.toString()
        )
        return parts.joinToString("::")
    }

    companion object {
        fun fromCancellationCatch(f: CancellationCatchFinding, occurrence: Int? = null): FindingIdentity {
            return FindingIdentity(
                category = FindingCategory.CANCELLATION_CATCH,
                modulePath = normalizeModulePath(f.module),
                repositoryPath = normalizePath(f.file),
                declaration = f.function.ifBlank { null },
                discriminator = f.catchType,
                occurrence = occurrence
            )
        }

        fun fromGlobalState(f: GlobalStateFinding, occurrence: Int? = null): FindingIdentity {
            return FindingIdentity(
                category = FindingCategory.GLOBAL_STATE,
                modulePath = normalizeModulePath(f.module),
                repositoryPath = normalizePath(f.file),
                declaration = f.declaration.ifBlank { null },
                discriminator = f.kind,
                occurrence = occurrence
            )
        }

        fun fromNondeterminism(f: NondeterminismFinding, occurrence: Int? = null): FindingIdentity {
            return FindingIdentity(
                category = FindingCategory.NONDETERMINISM,
                modulePath = normalizeModulePath(f.module),
                repositoryPath = normalizePath(f.file),
                declaration = f.source,
                discriminator = f.category + "::" + f.source,
                occurrence = occurrence
            )
        }

        fun fromStructuralHotspot(h: StructuralHotspot): FindingIdentity {
            return FindingIdentity(
                category = FindingCategory.STRUCTURAL_HOTSPOT,
                modulePath = normalizeModulePath(h.module),
                repositoryPath = normalizePath(h.path),
                declaration = h.declaration.ifBlank { null },
                discriminator = h.metric,
                occurrence = null
            )
        }

        /** Normalize a module path to canonical Gradle format (leading ':'). */
        private fun normalizeModulePath(module: String): String =
            if (module.startsWith(":")) module else ":$module"

        /** Normalize a source path to use consistent separators and remove redundant prefixes. */
        private fun normalizePath(path: String): String =
            path.replace("\\", "/").trimStart('/')
    }
}
