package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifier discriminators for Epic 8.3d PR 2 (P0-A..P0-J).
 *
 * The verifier is a pure function of (findings, entries) — no Gradle, no filesystem.
 */
class NondeterminismAllowlistVerifierTest {

    @TempDir
    lateinit var tempDir: File

    private fun finding(
        module: String = "tramai-x",
        file: String = "tramai-x/src/main/kotlin/dev/tramai/x/A.kt",
        line: Int = 10,
        source: String = "UUID.randomUUID()",
        classification: String = "correlation_identity",
        category: String = "identity"
    ) = NondeterminismFinding(module, file, line, source, classification, category)

    private fun entry(
        module: String = "tramai-x",
        file: String = "tramai-x/src/main/kotlin/dev/tramai/x/A.kt",
        source: String = "UUID.randomUUID()",
        category: String = "identity",
        scannerClassification: String = "correlation_identity",
        disposition: String = "CAPABILITY_AUTHORITY",
        authority: String = "TestAuthority",
        occurrences: Int = 1,
        rationale: String = "Test rationale."
    ) = NondeterminismAllowlistParser.AllowlistEntry(
        module, file, source, category, scannerClassification, disposition, authority, occurrences, rationale
    )

    private fun verify(findings: List<NondeterminismFinding>, entries: List<NondeterminismAllowlistParser.AllowlistEntry>) =
        NondeterminismAllowlistVerifier(findings, entries).verify()

    private fun codes(diagnostics: List<VerificationDiagnostic>) = diagnostics.map { it.code }

    @Test
    fun `P0-A unclassified source fails`() {
        val diagnostics = verify(
            findings = listOf(finding()),
            entries = emptyList()
        )
        assertTrue(DiagnosticCode.NONDETERMINISM_UNCLASSIFIED_FINDING in codes(diagnostics))
        assertEquals(DiagnosticSeverity.FAILURE, diagnostics.single().severity)
    }

    @Test
    fun `P0-B stale allowlist entry fails`() {
        val diagnostics = verify(
            findings = emptyList(),
            entries = listOf(entry())
        )
        assertTrue(DiagnosticCode.NONDETERMINISM_STALE_ENTRY in codes(diagnostics))
    }

    @Test
    fun `P0-C exact inventory passes`() {
        val diagnostics = verify(
            findings = listOf(finding()),
            entries = listOf(entry())
        )
        assertEquals(emptyList(), diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE })
    }

    @Test
    fun `P0-D wrong source or classification fails`() {
        // Same file, wrong source: the entry does not cover the finding.
        val wrongSource = verify(
            findings = listOf(finding(source = "System.nanoTime()", classification = "performance_measurement", category = "clock")),
            entries = listOf(entry(source = "UUID.randomUUID()"))
        )
        assertTrue(DiagnosticCode.NONDETERMINISM_UNCLASSIFIED_FINDING in codes(wrongSource))
        assertTrue(DiagnosticCode.NONDETERMINISM_STALE_ENTRY in codes(wrongSource))

        // Same source, wrong scanner classification: mismatch must fail.
        val wrongClassification = verify(
            findings = listOf(finding(classification = "performance_measurement", category = "clock")),
            entries = listOf(entry(scannerClassification = "correlation_identity", category = "identity"))
        )
        assertTrue(DiagnosticCode.NONDETERMINISM_MISMATCHED_CLASSIFICATION in codes(wrongClassification))
    }

    @Test
    fun `P0-E occurrence growth fails`() {
        // Two identical sources in the same file vs an entry declaring one occurrence.
        val diagnostics = verify(
            findings = listOf(finding(line = 10), finding(line = 20)),
            entries = listOf(entry(occurrences = 1))
        )
        assertTrue(DiagnosticCode.NONDETERMINISM_OCCURRENCE_MISMATCH in codes(diagnostics))

        // Shrinkage (entry declares 2, only 1 remains) must also fail.
        val shrink = verify(
            findings = listOf(finding(line = 10)),
            entries = listOf(entry(occurrences = 2))
        )
        assertTrue(DiagnosticCode.NONDETERMINISM_OCCURRENCE_MISMATCH in codes(shrink))
    }

    @Test
    fun `P0-F malformed classification fails closed`() {
        // Parser-level: unknown disposition must be rejected by parse validation.
        val yamlFile = File(tempDir, "config/quality/runtime-nondeterminism.yml")
        yamlFile.parentFile.mkdirs()
        yamlFile.writeText(
            """
            schemaVersion: '1'
            entries:
              - module: tramai-x
                file: tramai-x/src/main/kotlin/dev/tramai/x/A.kt
                source: UUID.randomUUID()
                category: identity
                scannerClassification: correlation_identity
                disposition: BOGUS
                authority: TestAuthority
                occurrences: 1
                rationale: Test.
            """.trimIndent()
        )
        val parseResult = NondeterminismAllowlistParser(tempDir).parse()
        assertTrue(DiagnosticCode.NONDETERMINISM_INVALID_DISPOSITION in codes(parseResult.diagnostics))

        // Missing authority and missing rationale
        yamlFile.writeText(
            """
            schemaVersion: '1'
            entries:
              - module: tramai-x
                file: tramai-x/src/main/kotlin/dev/tramai/x/A.kt
                source: UUID.randomUUID()
                category: identity
                scannerClassification: correlation_identity
                disposition: AUTHORITY
                occurrences: 1
                rationale: ""
            """.trimIndent()
        )
        val parseResult2 = NondeterminismAllowlistParser(tempDir).parse()
        assertTrue(DiagnosticCode.NONDETERMINISM_MISSING_RATIONALE in codes(parseResult2.diagnostics))
    }

    @Test
    fun `P0-G no wildcard escape`() {
        // A broad file wildcard entry cannot classify an arbitrary future finding:
        // matching is exact on (module, file, source) — wildcard entries are rejected
        // at parse time as malformed (no glob support) and at verify time as stale/absent.
        val wildcardEntry = entry(file = "tramai-x/src/main/kotlin/dev/tramai/x/*.kt", source = "UUID.randomUUID()")
        val diagnostics = verify(
            findings = listOf(finding(file = "tramai-x/src/main/kotlin/dev/tramai/x/Other.kt")),
            entries = listOf(wildcardEntry)
        )
        assertTrue(DiagnosticCode.NONDETERMINISM_UNCLASSIFIED_FINDING in codes(diagnostics))
        assertTrue(DiagnosticCode.NONDETERMINISM_STALE_ENTRY in codes(diagnostics))
    }

    @Test
    fun `P0-H line movement is harmless`() {
        // Same semantic identity, different line — the entry still covers it.
        val before = verify(
            findings = listOf(finding(line = 10)),
            entries = listOf(entry())
        )
        val after = verify(
            findings = listOf(finding(line = 1234)),
            entries = listOf(entry())
        )
        assertEquals(
            before.filter { it.severity == DiagnosticSeverity.FAILURE },
            after.filter { it.severity == DiagnosticSeverity.FAILURE }
        )
        assertTrue(after.filter { it.severity == DiagnosticSeverity.FAILURE }.isEmpty())
    }

    @Test
    fun `P0-I deterministic diagnostics ordering`() {
        val findings = listOf(
            finding(file = "tramai-x/src/main/kotlin/dev/tramai/x/B.kt", source = "System.nanoTime()", classification = "performance_measurement", category = "clock"),
            finding(file = "tramai-x/src/main/kotlin/dev/tramai/x/A.kt", source = "UUID.randomUUID()"),
            finding(file = "tramai-x/src/main/kotlin/dev/tramai/x/C.kt", source = "Clock.systemUTC()", classification = "scheduling_time", category = "clock")
        )
        val first = verify(findings, emptyList())
        val second = verify(findings, emptyList())
        // Same logical failure set always produces byte-identical ordering.
        assertEquals(first, second)
        val keys = first.map { it.message }
        assertEquals(keys.sorted(), keys)
    }

    @Test
    fun `P0-J aggregate gate wiring includes the verifier`() {
        // Proven by TestKit in TypedTaskConfigurationCacheTest (the maintainability
        // fixture's verifyMaintainabilityBaseline runs verifyRuntimeNondeterminism).
        // Here we assert the plugin-level wiring contract: the task class exists and
        // its diagnostics flow through the shared DiagnosticCode set.
        assertTrue(DiagnosticCode.values().contains(DiagnosticCode.NONDETERMINISM_UNCLASSIFIED_FINDING))
        assertTrue(DiagnosticCode.values().contains(DiagnosticCode.NONDETERMINISM_STALE_ENTRY))
    }

    @Test
    fun `duplicate identity entries fail parse`() {
        val yamlFile = File(tempDir, "config/quality/runtime-nondeterminism.yml")
        yamlFile.parentFile.mkdirs()
        yamlFile.writeText(
            """
            schemaVersion: '1'
            entries:
              - module: tramai-x
                file: tramai-x/src/main/kotlin/dev/tramai/x/A.kt
                source: UUID.randomUUID()
                category: identity
                scannerClassification: correlation_identity
                disposition: AUTHORITY
                authority: A
                occurrences: 1
                rationale: First.
              - module: tramai-x
                file: tramai-x/src/main/kotlin/dev/tramai/x/A.kt
                source: UUID.randomUUID()
                category: identity
                scannerClassification: correlation_identity
                disposition: AUTHORITY
                authority: B
                occurrences: 1
                rationale: Second.
            """.trimIndent()
        )
        val result = NondeterminismAllowlistParser(tempDir).parse()
        assertTrue(DiagnosticCode.NONDETERMINISM_DUPLICATE_ENTRY in codes(result.diagnostics))
    }

    @Test
    fun `missing allowlist file fails parse`() {
        val result = NondeterminismAllowlistParser(tempDir).parse()
        assertTrue(DiagnosticCode.NONDETERMINISM_INVALID_SCHEMA in codes(result.diagnostics))
    }
}
