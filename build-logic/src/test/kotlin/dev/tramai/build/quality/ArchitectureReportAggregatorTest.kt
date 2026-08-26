package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchitectureReportAggregatorTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `A1 forbidden edge fails dependency boundaries`() {
        fixtureDir()
        val catalog = ModuleCatalog(tempDir)
        assertTrue(catalog.parse().errors.isEmpty())
        val boundaries = ModuleBoundaries(tempDir).fromResult(ModuleBoundaries(tempDir).parse())
        val diagnostic = assertNotNull(boundaries.checkEdge(":tramai-core", ":tramai-openai", catalog))
        assertEquals(DiagnosticCode.FORBIDDEN_LAYER_EDGE, diagnostic.code)

        assertFailed("dependency-boundaries", diagnostic)
    }

    @Test
    fun `A2 dependency cycle fails dependency cycles`() {
        val cycles = ModuleGraphAnalyzer(
            MeasurementContext.fromDirectory(tempDir, ModuleCatalog(tempDir))
        ).findCycles(
            nodes = listOf(":a", ":b"),
            edges = listOf(
                DependencyEdge(":a", ":b", "api"),
                DependencyEdge(":b", ":a", "api"),
            ),
        )
        assertTrue(cycles.isNotEmpty())

        assertFailed(
            "dependency-cycles",
            VerificationDiagnostic.failure(DiagnosticCode.NEW_DEPENDENCY_CYCLE, "A -> B -> A"),
        )
    }

    @Test
    fun `A3 manifest drift fails module manifest`() {
        fixtureDir()
        val catalog = ModuleCatalog(tempDir).parse()
        val diagnostics = ModuleManifestVerifier.verify(
            catalogModules = catalog.modules,
            projectPaths = catalog.modules.keys - ":tramai-core",
            publishedPaths = ModuleManifest.publishableModulePaths(catalog.modules.values).toSet(),
            bomPaths = ModuleManifest.bomModulePaths(catalog.modules.values).toSet(),
        )
        val diagnostic = assertNotNull(diagnostics.firstOrNull { it.code == DiagnosticCode.MODULE_CATALOG_UNKNOWN_ENTRY })

        assertFailed("module-manifest", diagnostic)
    }

    @Test
    fun `A4 bom drift fails publishing topology`() {
        fixtureDir()
        val catalog = ModuleCatalog(tempDir).parse()
        val diagnostics = ModuleManifestVerifier.verify(
            catalogModules = catalog.modules,
            projectPaths = catalog.modules.keys,
            publishedPaths = ModuleManifest.publishableModulePaths(catalog.modules.values).toSet(),
            bomPaths = ModuleManifest.bomModulePaths(catalog.modules.values).toSet() - ":tramai-core",
        )
        val diagnostic = assertNotNull(diagnostics.firstOrNull { it.code == DiagnosticCode.MODULE_CATALOG_BOM_DRIFT })

        assertFailed("publishing-topology", diagnostic)
    }

    @Test
    fun `A5 api failure fails api architecture`() {
        assertFailed(
            "api-architecture",
            VerificationDiagnostic.failure(DiagnosticCode.API_COMPATIBILITY_FAILED, "API compatibility failed"),
        )
    }

    @Test
    fun `A6 global state failure fails global state`() {
        assertFailed(
            "global-state",
            VerificationDiagnostic.failure(DiagnosticCode.NEW_GLOBAL_STATE_FINDING, "New global state"),
        )
    }

    @Test
    fun `A7 cancellation failure fails cancellation safety`() {
        assertFailed(
            "cancellation-safety",
            VerificationDiagnostic.failure(DiagnosticCode.NEW_CANCELLATION_FINDING, "New cancellation finding"),
        )
    }

    @Test
    fun `A8 empty diagnostics pass all ten checks deterministically`() {
        val first = ArchitectureReportAggregator.aggregate(emptyChecks())
        val second = ArchitectureReportAggregator.aggregate(emptyChecks())

        assertEquals(ArchitectureCheckStatus.PASS, first.status)
        assertEquals(10, first.summary.checks)
        assertEquals(10, first.summary.passed)
        assertEquals(0, first.summary.failed)
        assertTrue(first.checks.all { it.diagnostics.isEmpty() })
        assertEquals(ArchitectureReportJson.toJson(first, tempDir), ArchitectureReportJson.toJson(second, tempDir))
    }

    @Test
    fun `A9 evidence source exception fails its checks and report is still writable`() {
        val target = emptyChecks()
        collectEvidence("baseline verification", setOf("module-manifest", "dependency-boundaries"), target) {
            throw IllegalStateException("catalog exploded")
        }
        val report = ArchitectureReportAggregator.aggregate(target)

        assertEquals(ArchitectureCheckStatus.FAIL, report.status)
        assertEquals(
            ArchitectureCheckStatus.FAIL,
            report.checks.single { it.id == "module-manifest" }.status,
        )
        assertEquals(
            ArchitectureCheckStatus.FAIL,
            report.checks.single { it.id == "dependency-boundaries" }.status,
        )
        // Report must be written even when evidence collection fails.
        val reportFile = File(tempDir, "architecture-report.json")
        ArchitectureReportJson.write(report, reportFile, tempDir)
        assertTrue(reportFile.isFile)
        val written = ReportNormalizer.readJson(reportFile, Map::class.java)
        assertEquals("FAIL", written["status"])
        assertEquals(2, (written["summary"] as Map<*, *>)["failed"].let { it as Number }.toInt())
    }

    @Test
    fun `A12 baseline evidence unavailable fails every baseline-backed check`() {
        val target = emptyChecks()
        routeBaselineDiagnostics(
            diagnostics = listOf(
                VerificationDiagnostic.failure(
                    DiagnosticCode.EMPTY_SECTION,
                    "Committed baseline not found: ${tempDir.absolutePath}/config/quality/0.6.0-baseline.json",
                ),
            ),
            target = target,
            baselineCheckIds = setOf(
                "module-manifest", "dependency-boundaries", "dependency-cycles",
                "global-state", "api-architecture", "protocol-catalog", "cancellation-safety",
            ),
            classify = { null },
        )
        val report = ArchitectureReportAggregator.aggregate(target)

        assertEquals(ArchitectureCheckStatus.FAIL, report.status)
        assertEquals(7, report.summary.failed)
        // Every baseline-backed check fails; the three non-baseline checks stay PASS.
        val baselineIds = setOf(
            "module-manifest", "dependency-boundaries", "dependency-cycles",
            "global-state", "api-architecture", "protocol-catalog", "cancellation-safety",
        )
        assertTrue(report.checks.filter { it.id in baselineIds }.all { it.status == ArchitectureCheckStatus.FAIL })
        assertTrue(report.checks.filter { it.id !in baselineIds }.all { it.status == ArchitectureCheckStatus.PASS })
        // Every baseline-backed check carries the evidence-failure diagnostic.
        assertTrue(
            report.checks.filter { it.id in baselineIds }.all { check ->
                check.diagnostics.any { it.code == DiagnosticCode.EMPTY_SECTION }
            },
        )
        // A failing report with an absolute path must sanitize it.
        val reportFile = File(tempDir, "architecture-report.json")
        ArchitectureReportJson.write(report, reportFile, tempDir)
        val json = reportFile.readText()
        assertTrue(json.contains("<repo-root>"))
        assertFalse(json.contains(tempDir.absolutePath))
    }

    @Test
    fun `A13 dependency evidence unavailable fails every baseline-backed check`() {
        val target = emptyChecks()
        routeBaselineDiagnostics(
            diagnostics = listOf(
                VerificationDiagnostic.failure(
                    DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED,
                    "Failed to resolve current production dependencies: broken",
                ),
            ),
            target = target,
            baselineCheckIds = setOf(
                "module-manifest", "dependency-boundaries", "dependency-cycles",
                "global-state", "api-architecture", "protocol-catalog", "cancellation-safety",
            ),
            classify = { null },
        )
        val report = ArchitectureReportAggregator.aggregate(target)

        assertEquals(ArchitectureCheckStatus.FAIL, report.status)
        assertEquals(7, report.summary.failed)
        val baselineIds = setOf(
            "module-manifest", "dependency-boundaries", "dependency-cycles",
            "global-state", "api-architecture", "protocol-catalog", "cancellation-safety",
        )
        assertTrue(
            report.checks.filter { it.id in baselineIds }.all { check ->
                check.diagnostics.any { it.code == DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED }
            },
        )
    }

    @Test
    fun `A14 failure report never leaks repository root path`() {
        val root = File(tempDir, "repo").apply { mkdirs() }
        val target = emptyChecks()
        collectEvidence("baseline verification", setOf("module-manifest"), target) {
            target.getValue("module-manifest") += VerificationDiagnostic.failure(
                DiagnosticCode.EMPTY_SECTION,
                "Committed baseline not found: ${root.absolutePath}/config/quality/0.6.0-baseline.json",
            )
        }
        val report = ArchitectureReportAggregator.aggregate(target)
        val json = ArchitectureReportJson.toJson(report, root)

        assertFalse(json.contains(root.absolutePath))
        assertTrue(json.contains("<repo-root>"))
    }

    @Test
    fun `A15 fail-soft dependency probe marker becomes typed evidence`() {
        val probeDir = File(tempDir, "probes").apply { mkdirs() }
        val okProbe = File(probeDir, "ok.json").apply {
            writeText(
                """[{"group":"dev.tramai","artifact":"tramai-core","selectedVersion":"1.0","requestedVersion":"1.0","direct":true,"configuration":"compileClasspath","selectionReason":"","dependencyPath":[":tramai-core"],"consumers":[":tramai-core"]}]""",
            )
        }
        val failedProbe = File(probeDir, "failed.json").apply {
            writeText(
                """[{"resolutionFailed":true,"message":"Failed to resolve :tramai-core:compileClasspath dependency org.example:missing:1.0: not found"}]""",
            )
        }

        val okEvidence = readDependencyProbeEvidence(listOf(okProbe))
        assertTrue(okEvidence.failures.isEmpty())
        assertEquals(1, okEvidence.resolvedRecords.size)

        val failedEvidence = readDependencyProbeEvidence(listOf(okProbe, failedProbe))
        assertEquals(1, failedEvidence.failures.size)
        assertTrue(failedEvidence.failures.single().contains("org.example:missing"))

        val missingEvidence = readDependencyProbeEvidence(listOf(File(probeDir, "absent.json")))
        assertEquals(1, missingEvidence.failures.size)
        assertTrue(missingEvidence.failures.single().contains("Missing dependency probe output"))
    }

    @Test
    fun `A10 deleted enrollment guard class fails store contracts by identity`() {
        val discovered = enrollmentArchitectureTestClasses - "dev.tramai.testing.ApprovalStoreTckEnrollmentArchitectureTest"
        val diagnostics = enrollmentGuardDiagnostics(discovered)

        val storeDiagnostics = diagnostics["store-contracts"].orEmpty()
        assertTrue(storeDiagnostics.any { it.message.contains("ApprovalStoreTckEnrollmentArchitectureTest") })
        assertTrue(diagnostics["provider-contracts"].orEmpty().isEmpty())
    }

    @Test
    fun `A10b renamed enrollment guard class fails by identity in both directions`() {
        val discovered = (enrollmentArchitectureTestClasses - "dev.tramai.testing.AuditStoreTckEnrollmentArchitectureTest") +
            "dev.tramai.testing.RenamedAuditStoreTckEnrollmentArchitectureTest"
        val diagnostics = enrollmentGuardDiagnostics(discovered)

        val storeDiagnostics = diagnostics["store-contracts"].orEmpty()
        assertTrue(storeDiagnostics.any { it.message.contains("AuditStoreTckEnrollmentArchitectureTest was not discovered") })
        assertTrue(storeDiagnostics.any { it.message.contains("RenamedAuditStoreTckEnrollmentArchitectureTest was discovered") })
    }

    @Test
    fun `A10c missing provider guard fails provider contracts`() {
        val discovered = enrollmentArchitectureTestClasses - "dev.tramai.testing.ProviderTckEnrollmentArchitectureTest"
        val diagnostics = enrollmentGuardDiagnostics(discovered)

        assertTrue(diagnostics["provider-contracts"].orEmpty().isNotEmpty())
    }

    @Test
    fun `A11 aggregator rejects unexpected check id set`() {
        val wrong = emptyChecks().toMutableMap().apply { remove("global-state") }
        assertFailsWith<IllegalArgumentException> {
            ArchitectureReportAggregator.aggregate(wrong)
        }
    }

    private fun assertFailed(checkId: String, diagnostic: VerificationDiagnostic) {
        val report = ArchitectureReportAggregator.aggregate(emptyChecks().also { it.getValue(checkId) += diagnostic })
        assertEquals(ArchitectureCheckStatus.FAIL, report.status)
        assertEquals(ArchitectureCheckStatus.FAIL, report.checks.single { it.id == checkId }.status)
    }

    private fun emptyChecks(): MutableMap<String, MutableList<VerificationDiagnostic>> =
        ArchitectureReportAggregator.checkIds
            .associateWith { mutableListOf<VerificationDiagnostic>() }
            .toMutableMap()

    private fun fixtureDir() {
        val config = File(tempDir, "config/quality").apply { mkdirs() }
        File(config, "module-catalog.yml").writeText(File(repoRoot(), "config/quality/module-catalog.yml").readText())
        File(config, "module-boundaries.yml").writeText(File(repoRoot(), "config/quality/module-boundaries.yml").readText())
    }

    private fun repoRoot(): File = System.getProperty("tramai.repositoryRoot")
        ?.let(::File)
        ?: error("tramai.repositoryRoot system property not set")
}
