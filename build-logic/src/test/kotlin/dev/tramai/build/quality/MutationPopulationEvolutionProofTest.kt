package dev.tramai.build.quality

import org.junit.jupiter.api.Test
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
}
