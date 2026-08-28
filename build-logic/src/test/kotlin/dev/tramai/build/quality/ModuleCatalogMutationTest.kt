package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mutation/discriminator suite proving the module-architecture guards actually guard.
 *
 * Every test copies the REAL committed manifest into a @TempDir fixture, applies one
 * deliberate mutation, and asserts the verifier fails with the exact expected
 * DiagnosticCode. Never touches config/quality/ during tests.
 */
class ModuleCatalogMutationTest {

    @TempDir
    lateinit var tempDir: File

    private fun fixtureDir(): File {
        val config = File(tempDir, "config/quality").apply { mkdirs() }
        File(config, "module-catalog.yml").writeText(realCatalogText())
        File(config, "module-boundaries.yml").writeText(realBoundariesText())
        return tempDir
    }

    private fun realCatalogText(): String =
        File(repoRoot(), "config/quality/module-catalog.yml").readText()

    private fun realBoundariesText(): String =
        File(repoRoot(), "config/quality/module-boundaries.yml").readText()

    private fun repoRoot(): File =
        System.getProperty("tramai.repositoryRoot")
            ?.let { File(it) }
            ?: error("tramai.repositoryRoot system property not set (wired by build-logic/build.gradle.kts)")

    private fun writeCatalog(text: String): File {
        val config = File(tempDir, "config/quality").apply { mkdirs() }
        val f = File(config, "module-catalog.yml")
        f.writeText(text)
        return f
    }

    private fun codes(result: ModuleCatalog.CatalogResult): Set<DiagnosticCode> =
        result.errors.map { it.code }.toSet()

    private fun codesOf(diagnostics: List<VerificationDiagnostic>): Set<DiagnosticCode> =
        diagnostics.map { it.code }.toSet()

    /** The exact committed block for :tramai-core (merge-anchor form). */
    private val coreBlock = """
        |  - path: ":tramai-core"
        |    description: "Core annotations, request models, provider registry, and exception types for Tramai."
        |    <<: *core
        |    layer: core-contracts
        |    publishability: published
        |    apiStability: stable
        |
    """.trimMargin()

    // ─── M1 — missing module ───

    @Test
    fun `M1 missing module - deleting a catalog entry fails project validation`() {
        fixtureDir()
        val mutated = realCatalogText().replace(coreBlock, "")
        assertNotEquals(realCatalogText(), mutated, "mutation must change content")
        writeCatalog(mutated)

        val catalog = ModuleCatalog(tempDir)
        val result = catalog.parse()
        assertEquals(emptySet(), codes(result), "parse itself must stay clean for a valid-remaining catalog")

        val diagnostics = mutableListOf<VerificationDiagnostic>()
        catalog.validateAgainstProjects(result.modules, listOf(":tramai-core"), diagnostics)
        assertTrue(
            codesOf(diagnostics).contains(DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY),
            "expected MODULE_CATALOG_MISSING_ENTRY, got ${codesOf(diagnostics)}"
        )
    }

    // ─── M2 — ghost module ───

    @Test
    fun `M2 ghost module - unknown catalog entry fails project validation`() {
        fixtureDir()
        val mutated = realCatalogText().replace(
            coreBlock,
            """
            |  - path: ":tramai-does-not-exist"
            |    layer: higher-capabilities
            |    publishability: internal
            |    apiStability: internal
            |    <<: *internal
            |
            |$coreBlock
            """.trimMargin()
        )
        writeCatalog(mutated)

        val catalog = ModuleCatalog(tempDir)
        val result = catalog.parse()
        // All REAL module paths are the project set; only the ghost is unknown.
        val realPaths = result.modules.keys
            .filter { !it.contains("does-not-exist") }
            .toList()
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        catalog.validateAgainstProjects(result.modules, realPaths, diagnostics)
        assertTrue(
            codesOf(diagnostics).contains(DiagnosticCode.MODULE_CATALOG_UNKNOWN_ENTRY),
            "expected MODULE_CATALOG_UNKNOWN_ENTRY, got ${codesOf(diagnostics)}"
        )
        assertTrue(
            diagnostics.any { it.message.contains(":tramai-does-not-exist") },
            "diagnostic must name the ghost module"
        )
    }

    // ─── M3 — forbidden core dependency ───

    @Test
    fun `M3 forbidden core dependency - core to openai is rejected`() {
        fixtureDir()
        val catalog = ModuleCatalog(tempDir)
        val catalogResult = catalog.parse()
        assertEquals(emptySet(), codes(catalogResult), "catalog must parse clean before edge check")

        val parsed = ModuleBoundaries(tempDir).parse()
        val moduleBoundaries = ModuleBoundaries(tempDir).fromResult(parsed)

        val diagnostic = moduleBoundaries.checkEdge(":tramai-core", ":tramai-openai", catalog)
        assertNotNull(diagnostic, "core -> openai must be a forbidden edge")
        assertEquals(DiagnosticCode.FORBIDDEN_LAYER_EDGE, diagnostic.code)
    }

    // ─── M4 — dependency cycle ───

    @Test
    fun `M4 dependency cycle - A to B to A is detected`() {
        val analyzer = ModuleGraphAnalyzer(
            MeasurementContext.fromDirectory(
                rootDir = tempDir,
                catalog = ModuleCatalog(tempDir)
            )
        )
        val cycles = analyzer.findCycles(
            nodes = listOf(":a", ":b"),
            edges = listOf(
                DependencyEdge(from = ":a", to = ":b", scope = "api"),
                DependencyEdge(from = ":b", to = ":a", scope = "api"),
            )
        )
        assertTrue(cycles.isNotEmpty(), "A -> B -> A must produce a cycle")
        assertTrue(
            cycles.any { it.toSet() == setOf(":a", ":b") },
            "cycle must contain exactly A and B, got $cycles"
        )
    }

    // ─── M5 — BOM drift (real discriminator) ───

    @Test
    fun `M5 BOM drift - manifest BOM set vs stale actual BOM set fails with BOM_DRIFT`() {
        fixtureDir()
        val catalog = ModuleCatalog(tempDir).parse()

        // Manifest-derived expectation includes tramai-core in the BOM.
        assertTrue(
            ModuleManifest.bomModulePaths(catalog.modules.values).contains(":tramai-core"),
            "fixture manifest must expect tramai-core in the BOM"
        )

        // Deliberately stale actual: BOM constraint graph omits tramai-core.
        val actualBom = ModuleManifest.bomModulePaths(catalog.modules.values).toSet() - ":tramai-core"
        val diagnostics = ModuleManifestVerifier.verify(
            catalogModules = catalog.modules,
            projectPaths = catalog.modules.keys,
            publishedPaths = ModuleManifest.publishableModulePaths(catalog.modules.values).toSet(),
            bomPaths = actualBom,
        )
        assertTrue(
            diagnostics.any { it.code == DiagnosticCode.MODULE_CATALOG_BOM_DRIFT },
            "expected MODULE_CATALOG_BOM_DRIFT, got ${diagnostics.map { it.code }}"
        )
        assertTrue(
            diagnostics.any { it.message.contains(":tramai-core") },
            "BOM drift diagnostic must name the diverging module"
        )
    }

    // ─── M6 — publishing drift (real discriminator) ───

    @Test
    fun `M6 publishing drift - manifest published set vs stale actual set fails with PUBLISHING_DRIFT`() {
        fixtureDir()
        val catalog = ModuleCatalog(tempDir).parse()

        // Manifest-derived expectation includes tramai-core as publishable.
        assertTrue(
            ModuleManifest.publishableModulePaths(catalog.modules.values).contains(":tramai-core"),
            "fixture manifest must expect tramai-core published"
        )

        // Deliberately stale actual: configured publication set omits tramai-core.
        val actualPublished = ModuleManifest.publishableModulePaths(catalog.modules.values).toSet() - ":tramai-core"
        val diagnostics = ModuleManifestVerifier.verify(
            catalogModules = catalog.modules,
            projectPaths = catalog.modules.keys,
            publishedPaths = actualPublished,
            bomPaths = ModuleManifest.bomModulePaths(catalog.modules.values).toSet(),
        )
        assertTrue(
            diagnostics.any { it.code == DiagnosticCode.MODULE_CATALOG_PUBLISHING_DRIFT },
            "expected MODULE_CATALOG_PUBLISHING_DRIFT, got ${diagnostics.map { it.code }}"
        )
        assertTrue(
            diagnostics.any { it.message.contains(":tramai-core") },
            "publishing drift diagnostic must name the diverging module"
        )
    }

    // ─── M6b — derivation drops internal modules (derivation-level guard) ───

    @Test
    fun `M6b derivation guard - internal module absent from derived publishable and BOM sets`() {
        fixtureDir()
        // Turn tramai-core into a fully VALID internal module; the derivation guard
        // (ModuleManifest) must drop it from both the publishable set and the BOM set.
        val mutated = realCatalogText().replace(
            coreBlock,
            """
            |  - path: ":tramai-core"
            |    <<: *internal
            |    layer: core-contracts
            |    publishability: internal
            |    apiStability: internal
            |
            """.trimMargin()
        )
        assertNotEquals(realCatalogText(), mutated, "mutation must change content")
        writeCatalog(mutated)

        val result = ModuleCatalog(tempDir).parse()
        assertEquals(emptySet(), codes(result), "mutation must stay parse-valid to test derivation")

        assertTrue(
            !ModuleManifest.publishableModulePaths(tempDir).contains(":tramai-core"),
            "internal module must not be publishable"
        )
        assertTrue(
            !ModuleManifest.bomModulePaths(tempDir).contains(":tramai-core"),
            "internal module must not be in BOM"
        )
    }

    // ─── M7 — blank owner / rationale ───

    @Test
    fun `M7 blank owner and rationale are rejected`() {
        fixtureDir()
        val mutated = realCatalogText().replace(
            coreBlock,
            """
            |  - path: ":tramai-core"
            |    description: "Core annotations, request models, provider registry, and exception types for Tramai."
            |    <<: *core
            |    layer: core-contracts
            |    publishability: published
            |    apiStability: stable
            |    owner: ""
            |
            """.trimMargin()
        )
        assertNotEquals(realCatalogText(), mutated, "owner mutation must change content")
        writeCatalog(mutated)

        val result = ModuleCatalog(tempDir).parse()
        assertTrue(
            codes(result).contains(DiagnosticCode.MODULE_CATALOG_BLANK_OWNER),
            "expected MODULE_CATALOG_BLANK_OWNER, got ${codes(result)}"
        )

        // Now blank rationale on the same base.
        val mutated2 = realCatalogText().replace(
            coreBlock,
            """
            |  - path: ":tramai-core"
            |    description: "Core annotations, request models, provider registry, and exception types for Tramai."
            |    <<: *core
            |    layer: core-contracts
            |    publishability: published
            |    apiStability: stable
            |    rationale: ""
            |
            """.trimMargin()
        )
        writeCatalog(mutated2)
        val result2 = ModuleCatalog(tempDir).parse()
        assertTrue(
            codes(result2).contains(DiagnosticCode.MODULE_CATALOG_BLANK_RATIONALE),
            "expected MODULE_CATALOG_BLANK_RATIONALE, got ${codes(result2)}"
        )
    }

    // ─── M8 — invalid policy ───

    @Test
    fun `M8 invalid policy - banana is rejected`() {
        fixtureDir()
        val mutated = realCatalogText().replace(
            coreBlock,
            """
            |  - path: ":tramai-core"
            |    description: "Core annotations, request models, provider registry, and exception types for Tramai."
            |    <<: *core
            |    layer: core-contracts
            |    publishability: published
            |    apiStability: stable
            |    dependencyPolicy: banana
            |
            """.trimMargin()
        )
        assertNotEquals(realCatalogText(), mutated, "policy mutation must change content")
        writeCatalog(mutated)

        val result = ModuleCatalog(tempDir).parse()
        assertTrue(
            codes(result).contains(DiagnosticCode.MODULE_CATALOG_INVALID_POLICY),
            "expected MODULE_CATALOG_INVALID_POLICY, got ${codes(result)}"
        )
        assertTrue(
            result.errors.any { it.message.contains("banana") },
            "error must name the unknown policy"
        )
    }

    // ─── D1 — schema v3 accepted, v2 rejected ───

    @Test
    fun `D1 schema v3 accepted and v2 rejected`() {
        fixtureDir()
        val result = ModuleCatalog(tempDir).parse()
        assertEquals(
            emptySet(),
            codes(result),
            "committed schema v3 catalog must parse clean, got ${codes(result)}"
        )

        val v2 = realCatalogText().replace("schemaVersion: \"3\"", "schemaVersion: \"2\"")
        assertNotEquals(realCatalogText(), v2, "schema downgrade must change content")
        writeCatalog(v2)
        val downgraded = ModuleCatalog(tempDir).parse()
        assertTrue(
            codes(downgraded).contains(DiagnosticCode.MODULE_CATALOG_INVALID_SCHEMA),
            "schema v2 must be rejected, got ${codes(downgraded)}"
        )
    }

    // ─── D2/D3 — published module missing/blank description ───

    @Test
    fun `D2 published module missing description is rejected`() {
        fixtureDir()
        val mutated = realCatalogText().replace(
            coreBlock,
            """
            |  - path: ":tramai-core"
            |    <<: *core
            |    layer: core-contracts
            |    publishability: published
            |    apiStability: stable
            |
            """.trimMargin()
        )
        assertNotEquals(realCatalogText(), mutated, "description-removal must change content")
        writeCatalog(mutated)

        val result = ModuleCatalog(tempDir).parse()
        assertTrue(
            codes(result).contains(DiagnosticCode.MODULE_CATALOG_MISSING_DESCRIPTION),
            "expected MODULE_CATALOG_MISSING_DESCRIPTION, got ${codes(result)}"
        )
    }

    @Test
    fun `D3 published module blank description is rejected with same code`() {
        fixtureDir()
        val mutated = realCatalogText().replace(
            coreBlock,
            """
            |  - path: ":tramai-core"
            |    description: "   "
            |    <<: *core
            |    layer: core-contracts
            |    publishability: published
            |    apiStability: stable
            |
            """.trimMargin()
        )
        writeCatalog(mutated)

        val result = ModuleCatalog(tempDir).parse()
        assertTrue(
            codes(result).contains(DiagnosticCode.MODULE_CATALOG_MISSING_DESCRIPTION),
            "blank description must produce MODULE_CATALOG_MISSING_DESCRIPTION, got ${codes(result)}"
        )
    }

    // ─── D4 — internal/excluded module may omit description ───

    @Test
    fun `D4 internal module without description parses clean`() {
        fixtureDir()
        val mutated = realCatalogText().replace(
            coreBlock,
            """
            |  - path: ":tramai-core"
            |    <<: *internal
            |    layer: core-contracts
            |    publishability: internal
            |    apiStability: internal
            |
            """.trimMargin()
        )
        assertNotEquals(realCatalogText(), mutated, "internal mutation must change content")
        writeCatalog(mutated)

        val result = ModuleCatalog(tempDir).parse()
        assertTrue(
            !codes(result).contains(DiagnosticCode.MODULE_CATALOG_MISSING_DESCRIPTION),
            "internal module must not require a description, got ${codes(result)}"
        )
    }

    // ─── D5 — exact legacy parity for every publishable module ───

    @Test
    fun `D5 published descriptions exactly match the legacy projectDescription policy`() {
        fixtureDir()
        val catalog = ModuleCatalog(tempDir).parse()
        assertEquals(emptySet(), codes(catalog), "catalog must parse clean, got ${codes(catalog)}")

        val legacy = LegacyPublicationDescriptions.byModule()
        catalog.modules.values
            .filter { it.publishability == ModulePublishability.PUBLISHED }
            .forEach { entry ->
                val moduleName = entry.path.removePrefix(":")
                assertEquals(
                    legacy[moduleName],
                    entry.description,
                    "catalog description for $moduleName must exactly match the pre-B8 policy",
                )
            }
    }
}
