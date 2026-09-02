package dev.tramai.build.quality

import dev.tramai.build.quality.TestQualityConfiguration.MutationTargetFamily
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 10.3c1 discriminator matrix M01-M20, with the high-value kill mutations
 * K1-K6 expressed as the assertions that fail if the protection regresses.
 */
class MutationMeasurementDiscriminatorTest {
    @TempDir
    lateinit var tempDir: Path

    private val families =
        mapOf(
            "policy" to MutationTargetFamily(listOf(":engine"), listOf("dev.tramai.policy.*"), listOf("dev.tramai.*")),
            "approval" to
                MutationTargetFamily(
                    listOf(":engine"),
                    listOf("dev.tramai.approval.*"),
                    listOf("dev.tramai.*"),
                ),
            "routing" to
                MutationTargetFamily(
                    listOf(":engine"),
                    listOf("dev.tramai.routing.*"),
                    listOf("dev.tramai.*"),
                ),
            "retry" to
                MutationTargetFamily(
                    listOf(":engine"),
                    listOf("dev.tramai.retry.*"),
                    listOf("dev.tramai.*"),
                ),
            "tools" to
                MutationTargetFamily(
                    listOf(":engine"),
                    listOf("dev.tramai.tools.*"),
                    listOf("dev.tramai.*"),
                ),
            "evidence" to
                MutationTargetFamily(
                    listOf(":engine"),
                    listOf("dev.tramai.evidence.*"),
                    listOf("dev.tramai.*"),
                ),
            "structuredOutput" to
                MutationTargetFamily(
                    listOf(":engine"),
                    listOf("dev.tramai.structured.*"),
                    listOf("dev.tramai.*"),
                ),
        )

    private val semantics = MutationAnalyzerSemantics("1.19.0", "1.22.1", listOf("MATH"), 4_000, 1.25)

    private fun overlappingFamilies(): Map<String, MutationTargetFamily> =
        mapOf(
            "policy" to MutationTargetFamily(listOf(":engine"), listOf("dev.tramai.policy.*"), listOf("dev.tramai.*")),
            "tools" to MutationTargetFamily(listOf(":engine"), listOf("dev.tramai.policy.*"), listOf("dev.tramai.*")),
        )

    private fun xml(content: String) = tempDir.resolve("mutations.xml").toFile().apply { writeText(content) }

    private fun mutation(
        status: String = "SURVIVED",
        klass: String = "dev.tramai.policy.Policy",
        descriptor: String = "()V",
        index: Int = 7,
        line: Int = 10,
    ) = """
        <mutations>
          <mutation status="$status">
            <sourceFile>Policy.kt</sourceFile>
            <mutatedClass>$klass</mutatedClass>
            <mutatedMethod>apply</mutatedMethod>
            <methodDescription>$descriptor</methodDescription>
            <lineNumber>$line</lineNumber>
            <mutator>org.pitest.mutationtest.engine.gregor.mutators.MathMutator</mutator>
            <indexes><index>$index</index></indexes>
            <blocks><block>1</block></blocks>
            <description>replaced int with +1</description>
          </mutation>
        </mutations>
        """.trimIndent()

    private fun report(
        family: String,
        content: String,
    ) = MutationReportParser().parse(":engine", family, xml(content))

    private fun allSeven(): List<ParsedMutationReport> =
        families.keys.sorted().map { family ->
            report(family, mutation(klass = "dev.tramai.$family.Policy"))
        }

    // --- M01/M02: report presence and population non-vacuity ---

    @Test
    fun `M01 configured family missing report fails`() {
        val reports = allSeven().filter { it.family != "routing" }
        assertFailsWith<GradleException> {
            MutationPopulationAggregator.aggregate(reports, families, "abc", semantics)
        }
    }

    @Test
    fun `M02 configured family producing zero mutants fails`() {
        val reports = allSeven().map { if (it.family == "routing") it.copy(mutants = emptyList()) else it }
        assertFailsWith<GradleException> {
            MutationPopulationAggregator.aggregate(reports, families, "abc", semantics)
        }
    }

    // --- M03-M05: parser strictness ---

    @Test
    fun `M03 malformed XML fails`() {
        assertFailsWith<GradleException> {
            MutationReportParser().parse(":engine", "policy", xml("<mutations>"))
        }
    }

    @Test
    fun `M04 absolute cache path leaks fail`() {
        assertFailsWith<GradleException> {
            MutationReportParser().parse(
                ":engine",
                "policy",
                xml(mutation().replace("Policy.kt", "/home/user/.gradle/caches/Policy.kt")),
            )
        }
    }

    @Test
    fun `M05 mutant missing required identity field fails`() {
        assertFailsWith<GradleException> {
            MutationReportParser().parse(
                ":engine",
                "policy",
                xml(mutation().replace("<mutatedMethod>apply</mutatedMethod>", "")),
            )
        }
    }

    // --- M06-M08: identity integrity ---

    @Test
    fun `M06 two mutants collide on identity inside one family fails`() {
        val one = report("policy", mutation(status = "KILLED"))
        val duplicate =
            one.copy(
                mutants = listOf(one.mutants.single(), one.mutants.single().copy(status = "SURVIVED")),
            )
        assertFailsWith<GradleException> {
            MutationPopulationAggregator.aggregate(
                allSeven().map { if (it.family == "policy") duplicate else it },
                families,
                "abc",
                semantics,
            )
        }
    }

    @Test
    fun `M07 overloaded methods distinguished by descriptor`() {
        val a = MutationReportParser().parse(":engine", "policy", xml(mutation(descriptor = "()V"))).mutants.single()
        val b =
            MutationReportParser()
                .parse(":engine", "policy", xml(mutation(descriptor = "(Ljava/lang/String;)V")))
                .mutants
                .single()
        assertTrue(a.identity != b.identity)
    }

    @Test
    fun `M08 different PIT mutation indexes are distinct`() {
        val a = MutationReportParser().parse(":engine", "policy", xml(mutation(index = 1))).mutants.single()
        val b = MutationReportParser().parse(":engine", "policy", xml(mutation(index = 2))).mutants.single()
        assertTrue(a.identity != b.identity)
    }

    // --- M09/M10: family membership vs identity ---

    @Test
    fun `M09 same mutant in two behavior families remains two memberships`() {
        val reports =
            listOf(
                report("policy", mutation(klass = "dev.tramai.policy.Policy")),
                report("tools", mutation(klass = "dev.tramai.policy.Policy")),
            )
        val baseline = MutationPopulationAggregator.aggregate(reports, overlappingFamilies(), "abc", semantics)
        assertEquals(2, baseline.mutants.size)
        assertEquals(setOf("policy", "tools"), baseline.mutants.map { it.family }.toSet())
    }

    @Test
    fun `M10 global identity aggregation does not double-count overlapping families`() {
        val reports =
            listOf(
                report("policy", mutation(klass = "dev.tramai.policy.Policy")),
                report("tools", mutation(klass = "dev.tramai.policy.Policy")),
            )
        val baseline = MutationPopulationAggregator.aggregate(reports, overlappingFamilies(), "abc", semantics)
        val uniqueIdentities = baseline.mutants.map { it.identity }.toSet()
        // Same underlying mutant appears once per family membership but its
        // stable identity is one value.
        assertEquals(1, uniqueIdentities.size)
        assertEquals(2, baseline.mutants.size)
    }

    // --- M11: unknown status ---

    @Test
    fun `M11 unknown PIT status fails closed`() {
        assertFailsWith<GradleException> {
            MutationReportParser().parse(
                ":engine",
                "policy",
                xml(mutation().replace("status=\"SURVIVED\"", "status=\"EXPLODED\"")),
            )
        }
    }

    // --- P0 review: v2 identity fields fail closed ---

    @Test
    fun `P0 missing methodDescription rejects the report`() {
        val stripped = mutation().replace("<methodDescription>()V</methodDescription>", "")
        assertFailsWith<GradleException> {
            MutationReportParser().parse(":engine", "policy", xml(stripped))
        }
    }

    @Test
    fun `P0 blank methodDescription rejects the report`() {
        val blanked = mutation().replace("<methodDescription>()V</methodDescription>", "<methodDescription></methodDescription>")
        assertFailsWith<GradleException> {
            MutationReportParser().parse(":engine", "policy", xml(blanked))
        }
    }

    @Test
    fun `P0 missing block rejects the report`() {
        val stripped = mutation().replace("<blocks><block>1</block></blocks>", "")
        assertFailsWith<GradleException> {
            MutationReportParser().parse(":engine", "policy", xml(stripped))
        }
    }

    @Test
    fun `P0 malformed block rejects the report`() {
        val broken = mutation().replace("<blocks><block>1</block></blocks>", "<blocks><block>abc</block></blocks>")
        assertFailsWith<GradleException> {
            MutationReportParser().parse(":engine", "policy", xml(broken))
        }
    }

    @Test
    fun `P0 missing index rejects the report`() {
        val stripped = mutation().replace("<indexes><index>7</index></indexes>", "")
        assertFailsWith<GradleException> {
            MutationReportParser().parse(":engine", "policy", xml(stripped))
        }
    }

    @Test
    fun `P0 malformed index rejects the report`() {
        val broken = mutation().replace("<indexes><index>7</index></indexes>", "<indexes><index>xyz</index></indexes>")
        assertFailsWith<GradleException> {
            MutationReportParser().parse(":engine", "policy", xml(broken))
        }
    }

    // --- M12-M15: status persistence ---

    private val singleFamily =
        mapOf(
            "policy" to MutationTargetFamily(listOf(":engine"), listOf("dev.tramai.policy.*"), listOf("dev.tramai.*")),
        )

    @Test
    fun `M12 killed mutant is persisted not discarded`() {
        val baseline =
            MutationPopulationAggregator.aggregate(
                listOf(report("policy", mutation(status = "KILLED"))),
                singleFamily,
                "abc",
                semantics,
            )
        assertEquals("KILLED", baseline.mutants.single().status)
        assertEquals("KILLED", baseline.mutants.single().outcome)
        assertEquals(1, baseline.mutants.size)
    }

    @Test
    fun `M13 survivor persisted exactly`() {
        val baseline =
            MutationPopulationAggregator.aggregate(
                listOf(report("policy", mutation(status = "SURVIVED"))),
                singleFamily,
                "abc",
                semantics,
            )
        val mutant = baseline.mutants.single()
        assertEquals("SURVIVED", mutant.status)
        assertEquals("dev.tramai.policy.Policy", mutant.className)
        assertEquals("apply", mutant.method)
        assertEquals("()V", mutant.methodDescription)
        assertEquals(7, mutant.index)
        assertEquals(10, mutant.line)
    }

    @Test
    fun `M14 NO_COVERAGE persisted exactly with NON_KILLED outcome`() {
        val baseline =
            MutationPopulationAggregator.aggregate(
                listOf(report("policy", mutation(status = "NO_COVERAGE"))),
                singleFamily,
                "abc",
                semantics,
            )
        assertEquals("NO_COVERAGE", baseline.mutants.single().status)
        assertEquals("NON_KILLED", baseline.mutants.single().outcome)
    }

    @Test
    fun `M15 TIMED_OUT persisted exactly with NON_KILLED outcome`() {
        val baseline =
            MutationPopulationAggregator.aggregate(
                listOf(report("policy", mutation(status = "TIMED_OUT"))),
                singleFamily,
                "abc",
                semantics,
            )
        assertEquals("TIMED_OUT", baseline.mutants.single().status)
        assertEquals("NON_KILLED", baseline.mutants.single().outcome)
    }

    @Test
    fun `C7 canonical outcome is stable across non-killed status races`() {
        // A TIMED_OUT<->SURVIVED scheduler flip must not change the ratchet
        // state: both canonicalize to NON_KILLED.
        assertEquals("NON_KILLED", MutationOutcome.canonical("TIMED_OUT"))
        assertEquals("NON_KILLED", MutationOutcome.canonical("SURVIVED"))
        assertEquals("NON_KILLED", MutationOutcome.canonical("NO_COVERAGE"))
        assertEquals("KILLED", MutationOutcome.canonical("KILLED"))
    }

    @Test
    fun `tool failure statuses fail the measurement instead of becoming NON_KILLED`() {
        // NON_VIABLE / MEMORY_ERROR / RUN_ERROR / REMOVED / NOT_STARTED and
        // any unknown status must fail canonicalization (fail closed), never
        // silently become an approved NON_KILLED mutant.
        for (status in listOf("NON_VIABLE", "MEMORY_ERROR", "RUN_ERROR", "REMOVED", "NOT_STARTED", "EXPLODED")) {
            assertFailsWith<GradleException>(status) {
                MutationOutcome.canonical(status)
            }
        }
    }

    // --- M16/M17: determinism and family presence ---

    @Test
    fun `M16 same raw input produces byte-identical normalized population`() {
        val reports = allSeven()
        val a = MutationPopulationAggregator.aggregate(reports, families, "abc", semantics)
        val b = MutationPopulationAggregator.aggregate(reports, families, "abc", semantics)
        val jsonA = ReportNormalizer.toJson(a)
        val jsonB = ReportNormalizer.toJson(b)
        assertEquals(jsonA, jsonB)
    }

    @Test
    fun `M16 canonical outcomes identical when only non-killed statuses race`() {
        // Simulate the C7 finding: two identical populations except a
        // TIMED_OUT<->SURVIVED flip on one mutant. Canonical outcomes must be
        // identical — the ratchet state is unchanged.
        val policyReport = report("policy", mutation(status = "TIMED_OUT"))
        val flipped =
            policyReport.copy(
                mutants = listOf(policyReport.mutants.single().copy(status = "SURVIVED")),
            )
        val a = MutationPopulationAggregator.aggregate(allSeven(), families, "abc", semantics)
        val b =
            MutationPopulationAggregator.aggregate(
                allSeven().map { if (it.family == "policy") flipped else it },
                families,
                "abc",
                semantics,
            )
        val outcomesA = a.mutants.map { it.identity to it.outcome }.toMap()
        val outcomesB = b.mutants.map { it.identity to it.outcome }.toMap()
        assertEquals(outcomesA, outcomesB)
    }

    @Test
    fun `M17 all seven configured families appear in baseline`() {
        val baseline = MutationPopulationAggregator.aggregate(allSeven(), families, "abc", semantics)
        assertEquals(families.keys.sorted(), baseline.byFamily.keys.sorted())
    }

    // --- M18/M19: artifact integrity ---

    @Test
    fun `M18 blank identity in generated baseline fails`() {
        val withBlank = report("policy", mutation()).mutants.single().copy(identity = "")
        assertFailsWith<GradleException> {
            MutationPopulationAggregator.aggregate(
                allSeven().map { if (it.family == "policy") it.copy(mutants = listOf(withBlank)) else it },
                families,
                "abc",
                semantics,
            )
        }
    }

    @Test
    fun `M19 analyzer and identity schema metadata missing fails`() {
        val bare = MutationAnalyzerSemantics("", "", emptyList())
        assertFailsWith<GradleException> {
            MutationPopulationAggregator.aggregate(allSeven(), families, "abc", bare)
        }
        // Unpinned timeout is also a C1 failure (timeout is mutation semantics).
        val noTimeout = MutationAnalyzerSemantics("1.19.0", "1.22.1", listOf("MATH"), 0, 1.25)
        assertFailsWith<GradleException> {
            MutationPopulationAggregator.aggregate(allSeven(), families, "abc", noTimeout)
        }
        // With pinned semantics the artifact carries them (C1).
        val baseline = MutationPopulationAggregator.aggregate(allSeven(), families, "abc", semantics)
        assertEquals("1.19.0", baseline.analyzer.pluginVersion)
        assertEquals("1.22.1", baseline.analyzer.engineVersion)
        assertEquals(listOf("MATH"), baseline.analyzer.mutators)
        assertEquals(4_000, baseline.analyzer.timeoutConst)
        assertEquals("2", baseline.identitySchemaVersion)
    }

    // --- M20: identity excludes line relocation ---

    @Test
    fun `M20 identity excludes source line relocation`() {
        val a = MutationReportParser().parse(":engine", "policy", xml(mutation(line = 10))).mutants.single()
        val b = MutationReportParser().parse(":engine", "policy", xml(mutation(line = 250))).mutants.single()
        assertEquals(a.identity, b.identity)
    }
}
