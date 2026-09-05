package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MutationRatchetAuthorityLoader integration tests against the REAL repository
 * (system property `tramai.repositoryRoot`, wired by build-logic/build.gradle.kts).
 *
 * These prove the base-authority loading path actually reads committed
 * `mutation-baseline.json` + `mutation-classifications.yml` + `test-quality.yml`
 * at a given SHA, that base SHA resolution mirrors cancellation safety /
 * coverage (explicit property wins; invalid SHA fails hard), and — critically —
 * that the certified 10.3c2 authority passes its own ratchet.
 */
class MutationRatchetAuthorityTest {
    private val repoRoot =
        File(
            requireNotNull(System.getProperty("tramai.repositoryRoot")) {
                "tramai.repositoryRoot must be set (build-logic/build.gradle.kts test wiring); " +
                    "cannot resolve the real repository for authority loader tests"
            },
        )
    private val headSha = git("rev-parse", "HEAD").trim()

    private fun git(vararg args: String): String {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
        val out = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "git ${args.joinToString(" ")} failed with exit $exitCode: ${out.trim()}"
        }
        return out
    }

    @Test
    fun `load reads the certified authority at head`() {
        val authority = MutationRatchetAuthorityLoader.load(repoRoot, headSha)
        assertEquals(headSha, authority.baseSha)
        // Certified 10.3c2 population: 2384 mutants, 1595 KILLED, 789 NON_KILLED.
        assertEquals(2384, authority.population.mutants.size)
        assertEquals(1595, authority.population.mutants.count { it.outcome == "KILLED" })
        assertEquals(789, authority.population.mutants.count { it.outcome == "NON_KILLED" })
        assertEquals("5856530e0fa4a9538b0828b89d730812f4cc5cac", authority.population.measuredCommit)
        assertEquals("2", authority.population.identitySchemaVersion)
        assertEquals(7, authority.population.byFamily.size)
        assertEquals(7, authority.targetFamilies.size)
        // Pinned PIT semantics (identity schema v2).
        assertEquals("1.19.0", authority.population.analyzer.pluginVersion)
        assertEquals("1.22.1", authority.population.analyzer.engineVersion)
        assertEquals(11, authority.population.analyzer.mutators.size)
        assertEquals(4_000, authority.population.analyzer.timeoutConst)
        assertEquals(1.25, authority.population.analyzer.timeoutFactor)
        // 23 adjudicated classifications, all referencing NON_KILLED mutants.
        assertEquals(23, authority.classifications.classifications.size)
        assertTrue(
            authority.classifications.classifications.all {
                authority.population.mutants.any { m -> m.identity == it.id && m.outcome == "NON_KILLED" }
            },
        )
    }

    @Test
    fun `certified authority passes its own ratchet against identical candidate`() {
        val authority = MutationRatchetAuthorityLoader.load(repoRoot, headSha)
        val candidatePopulation =
            ReportNormalizer.readJson(
                File(repoRoot, "config/quality/mutation-baseline.json"),
                MutationPopulationBaseline::class.java,
            )
        val candidateClassifications = MutationClassificationLoader.load(repoRoot)
        val candidateConfiguration = TestQualityConfiguration.load(repoRoot)
        val failures =
            MutationRatchetVerifier()
                .verify(
                    authority,
                    MutationRatchetCandidate(
                        population = candidatePopulation,
                        classifications = candidateClassifications,
                        targetFamilies = candidateConfiguration.mutation.targetFamilies,
                    ),
                    executable = MutationPopulationAggregator.canonicalSemantics(),
                ).filter { it.severity == DiagnosticSeverity.FAILURE }
        assertEquals(
            emptyList(),
            failures.map { "${it.code}: ${it.message}" },
            "the certified authority must be self-consistent under its own ratchet",
        )
    }

    @Test
    fun `explicit valid base sha is accepted`() {
        val resolved = MutationRatchetAuthorityLoader.resolveBaseSha(repoRoot, headSha)
        assertEquals(headSha, resolved)
    }

    @Test
    fun `absent base sha falls back to merge-base HEAD origin master`() {
        val resolved = MutationRatchetAuthorityLoader.resolveBaseSha(repoRoot, null)
        assertTrue(resolved.matches(Regex("[0-9a-f]{40}")), "expected merge-base sha, got: $resolved")
    }

    @Test
    fun `invalid base sha fails hard`() {
        val e = runCatching { MutationRatchetAuthorityLoader.resolveBaseSha(repoRoot, "deadbeef") }
        assertTrue(e.isFailure, "invalid SHA must be a hard failure")
        assertTrue(e.exceptionOrNull()!!.message!!.contains("not a valid commit"))
    }

    @Test
    fun `unresolvable base sha fails hard`() {
        val e =
            runCatching {
                MutationRatchetAuthorityLoader.resolveBaseSha(repoRoot, "0000000000000000000000000000000000000000")
            }
        assertTrue(e.isFailure, "unresolvable SHA must be a hard failure")
    }

    @Test
    fun `missing base authority file fails closed`() {
        // The authority must contain mutation-baseline.json at the base SHA;
        // a SHA before 10.3c1 has none and must fail hard, never skip the gate.
        val e =
            runCatching {
                MutationRatchetAuthorityLoader.load(repoRoot, "0000000000000000000000000000000000000000")
            }
        assertTrue(e.isFailure, "a base SHA without the mutation authority must fail hard")
    }
}
