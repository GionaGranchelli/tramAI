package dev.tramai.build.quality

import dev.tramai.build.quality.TestQualityConfiguration.MutationTargetFamily
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MutationPopulationTest {
    private val families =
        mapOf(
            "routing" to
                MutationTargetFamily(
                    modules = listOf(":engine"),
                    targetClasses = listOf("dev.tramai.Router"),
                    targetTests = listOf("dev.tramai.*"),
                ),
        )

    private val semantics =
        MutationAnalyzerSemantics(
            pluginVersion = MutationProbeInitScript.PIT_PLUGIN_VERSION,
            engineVersion = MutationProbeInitScript.PIT_ENGINE_VERSION,
            mutators = MutationProbeInitScript.PIT_MUTATORS,
            timeoutConst = MutationProbeInitScript.TIMEOUT_CONST_MILLIS.toInt(),
            timeoutFactor = MutationProbeInitScript.TIMEOUT_FACTOR,
        )

    private fun record(
        status: String = "KILLED",
        index: Int = 0,
        family: String = "routing",
        module: String = ":engine",
    ) = MutationRecord(
        module = module,
        family = family,
        status = status,
        sourceFile = "Router.kt",
        className = "dev.tramai.Router",
        method = "route",
        methodDescription = "()V",
        line = 1,
        mutator = "MATH",
        description = "d",
        identity =
            MutationIdentity(
                module,
                "dev.tramai.Router",
                "route",
                "()V",
                "MATH",
                "d",
                block = 0,
                index = index,
            ).stableKey(),
        block = 0,
        index = index,
    )

    @Test
    fun `a configured module that reports nothing fails even when its family stays non-vacuous`() {
        // The under-count scenario: the family total is non-zero, one configured module is missing
        // entirely. Family-level non-emptiness alone accepts this and persists a shrunken population.
        val twoModuleFamily =
            mapOf(
                "evidence" to
                    MutationTargetFamily(
                        modules = listOf(":engine", ":security"),
                        targetClasses = listOf("dev.tramai.Router"),
                        targetTests = listOf("dev.tramai.*"),
                    ),
            )
        val failure =
            assertFailsWith<GradleException> {
                MutationPopulationAggregator.aggregate(
                    listOf(
                        ParsedMutationReport(
                            ":engine",
                            "evidence",
                            listOf(record(family = "evidence", status = "KILLED")),
                        ),
                    ),
                    twoModuleFamily,
                    "abc",
                    semantics,
                )
            }
        assertTrue(
            failure.message.orEmpty().contains(":security"),
            "the unmeasured module must be named: ${failure.message}",
        )
    }

    @Test
    fun `every configured module reporting yields the configured module topology`() {
        val twoModuleFamily =
            mapOf(
                "evidence" to
                    MutationTargetFamily(
                        modules = listOf(":engine", ":security"),
                        targetClasses = listOf("dev.tramai.Router"),
                        targetTests = listOf("dev.tramai.*"),
                    ),
            )
        val baseline =
            MutationPopulationAggregator.aggregate(
                listOf(
                    ParsedMutationReport(
                        ":engine",
                        "evidence",
                        listOf(record(family = "evidence", status = "KILLED", index = 1)),
                    ),
                    ParsedMutationReport(
                        ":security",
                        "evidence",
                        listOf(record(family = "evidence", status = "KILLED", index = 2, module = ":security")),
                    ),
                ),
                twoModuleFamily,
                "abc",
                semantics,
            )
        assertEquals(listOf(":engine", ":security"), baseline.byFamily.getValue("evidence").modules)
        assertEquals(2, baseline.byFamily.getValue("evidence").totalMutants)
    }

    @Test
    fun `killed mutants are persisted not discarded`() {
        val baseline =
            MutationPopulationAggregator.aggregate(
                listOf(ParsedMutationReport(":engine", "routing", listOf(record(status = "KILLED")))),
                families,
                "abc",
                semantics,
            )
        assertEquals(1, baseline.mutants.size)
        assertEquals("KILLED", baseline.mutants.single().status)
        assertEquals(1, baseline.byFamily.getValue("routing").killedMutants)
    }

    @Test
    fun `survivor and NO_COVERAGE and TIMED_OUT are persisted exactly`() {
        val baseline =
            MutationPopulationAggregator.aggregate(
                listOf(
                    ParsedMutationReport(
                        ":engine",
                        "routing",
                        listOf(
                            record(status = "SURVIVED", index = 1),
                            record(status = "NO_COVERAGE", index = 2),
                            record(status = "TIMED_OUT", index = 3),
                        ),
                    ),
                ),
                families,
                "abc",
                semantics,
            )
        assertEquals(setOf("SURVIVED", "NO_COVERAGE", "TIMED_OUT"), baseline.mutants.map { it.status }.toSet())
        val fam = baseline.byFamily.getValue("routing")
        assertEquals(1, fam.survivedMutants)
        assertEquals(1, fam.noCoverageMutants)
        assertEquals(1, fam.timedOutMutants)
    }

    @Test
    fun `same identity twice in one family module fails`() {
        assertFailsWith<GradleException> {
            MutationPopulationAggregator.aggregate(
                listOf(
                    ParsedMutationReport(
                        ":engine",
                        "routing",
                        listOf(record(status = "KILLED", index = 0), record(status = "SURVIVED", index = 0)),
                    ),
                ),
                families,
                "abc",
                semantics,
            )
        }
    }

    @Test
    fun `same underlying mutant in two families is two memberships not a collision`() {
        val twoFamilies =
            mapOf(
                "routing" to
                    MutationTargetFamily(
                        listOf(":engine"),
                        listOf("dev.tramai.Router"),
                        listOf("dev.tramai.*"),
                    ),
                "retry" to
                    MutationTargetFamily(
                        listOf(":engine"),
                        listOf("dev.tramai.Router"),
                        listOf("dev.tramai.*"),
                    ),
            )
        val baseline =
            MutationPopulationAggregator.aggregate(
                listOf(
                    ParsedMutationReport(":engine", "routing", listOf(record(family = "routing", status = "SURVIVED"))),
                    ParsedMutationReport(":engine", "retry", listOf(record(family = "retry", status = "SURVIVED"))),
                ),
                twoFamilies,
                "abc",
                semantics,
            )
        assertEquals(2, baseline.mutants.size)
        assertEquals(setOf("routing", "retry"), baseline.mutants.map { it.family }.toSet())
        // Global aggregation does not double-count into a single total; byFamily keeps both.
        assertEquals(1, baseline.byFamily.getValue("routing").totalMutants)
        assertEquals(1, baseline.byFamily.getValue("retry").totalMutants)
    }

    @Test
    fun `configured family with zero mutants fails`() {
        assertFailsWith<GradleException> {
            MutationPopulationAggregator.aggregate(
                listOf(ParsedMutationReport(":engine", "routing", emptyList())),
                families,
                "abc",
                semantics,
            )
        }
    }

    @Test
    fun `missing family fails and unconfigured family fails`() {
        assertFailsWith<GradleException> {
            MutationPopulationAggregator.aggregate(emptyList(), families, "abc", semantics)
        }
        assertFailsWith<GradleException> {
            MutationPopulationAggregator.aggregate(
                listOf(ParsedMutationReport(":engine", "ghost", listOf(record(family = "ghost")))),
                families,
                "abc",
                semantics,
            )
        }
    }

    @Test
    fun `same raw input produces deterministic sorted output`() {
        val reports =
            listOf(
                ParsedMutationReport(
                    ":engine",
                    "routing",
                    listOf(
                        record(status = "KILLED", index = 3),
                        record(status = "SURVIVED", index = 1),
                        record(status = "NO_COVERAGE", index = 2),
                    ),
                ),
            )
        val a = MutationPopulationAggregator.aggregate(reports, families, "abc", semantics)
        val b = MutationPopulationAggregator.aggregate(reports, families, "abc", semantics)
        assertEquals(a.mutants.map { it.identity }, b.mutants.map { it.identity })
        assertEquals(a.byFamily, b.byFamily)
    }
}
