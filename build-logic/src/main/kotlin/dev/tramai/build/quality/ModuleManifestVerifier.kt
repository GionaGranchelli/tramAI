package dev.tramai.build.quality

/**
 * Pure, testable verification of the authoritative module architecture manifest
 * against independent realities.
 *
 * The manifest is the single source of truth. This verifier does NOT compare the
 * manifest with itself — every check compares the manifest-derived expectation
 * against a value obtained from an independent source (the Gradle project model,
 * the configured publication set, the configured BOM constraint graph).
 */
object ModuleManifestVerifier {

    /**
     * @param catalogModules parsed manifest entries (expected truth)
     * @param projectPaths actual Gradle project paths from the settings/project model
     * @param publishedPaths actual configured publishable set (what the build scripts
     *                       wire into publication/release tasks)
     * @param bomPaths actual configured BOM constraint module paths (what tramai-bom
     *                 declares in its api configuration)
     */
    fun verify(
        catalogModules: Map<String, ModuleCatalog.ModuleEntry>,
        projectPaths: Set<String>,
        publishedPaths: Set<String>,
        bomPaths: Set<String>,
    ): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()

        // Settings ↔ manifest exact-set equality.
        for (projPath in projectPaths) {
            if (projPath !in catalogModules) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY,
                    "Gradle project '$projPath' has no module-catalog entry")
            }
        }
        for (catPath in catalogModules.keys) {
            if (catPath !in projectPaths) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CATALOG_UNKNOWN_ENTRY,
                    "Module-catalog entry '$catPath' does not exist as a Gradle project")
            }
        }

        // Publishing drift: the configured publication set must equal the
        // manifest-published set. Publishing is DERIVED from the manifest in this
        // build, so this check is the regression lock that fires if the derivation
        // is replaced by a literal list or the manifest and build disagree.
        val expectedPublished = ModuleManifest.publishableModulePaths(catalogModules.values).toSet()
        if (publishedPaths != expectedPublished) {
            diagnostics += VerificationDiagnostic.failure(
                DiagnosticCode.MODULE_CATALOG_PUBLISHING_DRIFT,
                "Publishing drift: configured publication set ${publishedPaths.sorted()} " +
                    "but manifest requires ${expectedPublished.sorted()}")
        }

        // BOM drift: the actual BOM constraint graph (read from Gradle's model of
        // tramai-bom's api configuration) must equal the manifest-derived BOM set.
        val expectedBom = ModuleManifest.bomModulePaths(catalogModules.values).toSet()
        if (bomPaths != expectedBom) {
            diagnostics += VerificationDiagnostic.failure(
                DiagnosticCode.MODULE_CATALOG_BOM_DRIFT,
                "BOM drift: configured BOM constraints ${bomPaths.sorted()} " +
                    "but manifest requires ${expectedBom.sorted()}")
        }

        return diagnostics
    }
}
