package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MutationPopulationEvolutionProofTest : MutationRatchetTestSupport() {
    @Test
    fun `exact comparison detects identity status family and module drift`() {
        val candidate = population(listOf(row("v1")))
        assertTrue(
            MutationPopulationEvolutionProof
                .exactComparison(population(listOf(row("v2"))), candidate)
                .diagnostics
                .any { it.message.contains("absent") },
        )
        assertTrue(
            MutationPopulationEvolutionProof
                .exactComparison(
                    population(listOf(row("v1", status = "KILLED", outcome = "KILLED"))),
                    candidate,
                ).diagnostics
                .any { it.message.contains("raw status") },
        )
        assertTrue(
            MutationPopulationEvolutionProof
                .exactComparison(
                    population(listOf(row("v1").copy(family = retryFamily))),
                    candidate,
                ).diagnostics
                .any { it.message.contains("family") },
        )
        assertTrue(
            MutationPopulationEvolutionProof
                .exactComparison(
                    population(listOf(row("v1").copy(module = ":other"))),
                    candidate,
                ).diagnostics
                .any { it.message.contains("module") },
        )
    }

    @Test
    fun `identical populations produce an evolution proof`() {
        val population = population(listOf(row("v1")))
        val comparison = MutationPopulationEvolutionProof.exactComparison(population, population)
        assertTrue(comparison.diagnostics.isEmpty())
        assertTrue(comparison.proof != null)
    }

    // ── T1 / T2 / T8: the authority projection is authority content only ──
    //
    // The empirical defect: four complete unrestricted campaigns at identical effective PIT inputs
    // produced four distinct raw-status digests — three identities of 2544 oscillating only between
    // SURVIVED and TIMED_OUT, all NON_KILLED in every campaign — while the authority content was
    // byte-identical. M34 bound the raw-status digest, so a scheduler race invalidated adjudicated
    // authority. Raw status stays authoritative for the measurement proof (M21) and for each
    // individual authorization's exact row (M32); it must not be population authority (C7).

    @Test
    fun `T1 a neighbour's raw status movement leaves the authority projection unchanged`() {
        val before = population(listOf(row("target"), row("neighbour", status = "SURVIVED")))
        val after = population(listOf(row("target"), row("neighbour", status = "TIMED_OUT")))
        assertEquals(digestOf(before), digestOf(after))
        // ...while the raw-exact measurement proof still distinguishes them, so nothing was relaxed
        // where raw status genuinely is authority.
        assertNotEquals(rawDigestOf(before), rawDigestOf(after))
    }

    @Test
    fun `T2 an outcome change changes the authority projection`() {
        val survivor = population(listOf(row("target")))
        val killer = population(listOf(row("target", status = "KILLED", outcome = "KILLED")))
        assertNotEquals(digestOf(survivor), digestOf(killer))
    }

    @Test
    fun `T8 analyzer semantics change the authority projection`() {
        val base = population(listOf(row("target")))
        val drifted =
            population(
                listOf(row("target")),
                analyzer = semantics.copy(timeoutConst = semantics.timeoutConst + 1),
            )
        assertNotEquals(digestOf(base), digestOf(drifted))
    }
}
