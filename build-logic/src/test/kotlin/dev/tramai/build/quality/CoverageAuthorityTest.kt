package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CoverageAuthorityLoader integration tests against the REAL repository
 * (system property `tramai.repositoryRoot`, wired by build-logic/build.gradle.kts).
 *
 * These prove the base-authority loading path actually reads committed
 * `test-quality.yml` + `coverage-baseline.json` at a given SHA, and that base
 * SHA resolution mirrors cancellation safety (explicit property wins; invalid
 * SHA fails hard).
 */
class CoverageAuthorityTest {
    private val repoRoot = File(System.getProperty("tramai.repositoryRoot"))
    private val headSha = git("rev-parse", "HEAD").trim()

    @Test
    fun `load reads committed test-quality and coverage baseline at head`() {
        val authority = CoverageAuthorityLoader.load(repoRoot, headSha)
        assertEquals(headSha, authority.baseSha)
        assertEquals("measured", authority.baseline.status)
        assertEquals(9, authority.baseline.criticalModules.size)
        assertEquals(9, authority.configuration.criticalModules.size)
        // The committed baseline must be internally consistent (B20–B22 clean).
        assertEquals(authority.baseline.byModule.keys, authority.baseline.criticalModules.keys)
        assertTrue(authority.baseline.overallLineCoverage > 90.0, "expected ~92.5% overall lines")
    }

    @Test
    fun `explicit valid base sha is accepted`() {
        val resolved = CoverageAuthorityLoader.resolveBaseSha(repoRoot, headSha)
        assertEquals(headSha, resolved)
    }

    @Test
    fun `absent base sha falls back to merge-base HEAD origin master`() {
        // Locally origin/master exists; resolution must return a 40-hex sha.
        val resolved = CoverageAuthorityLoader.resolveBaseSha(repoRoot, null)
        assertTrue(resolved.matches(Regex("[0-9a-f]{40}")), "expected merge-base sha, got: $resolved")
    }

    @Test
    fun `invalid base sha fails hard`() {
        val e = runCatching { CoverageAuthorityLoader.resolveBaseSha(repoRoot, "deadbeef") }
        assertTrue(e.isFailure, "invalid SHA must be a hard failure")
        assertTrue(e.exceptionOrNull()!!.message!!.contains("not a valid commit"))
    }

    @Test
    fun `unresolvable base sha fails hard`() {
        // A commit that does not exist cannot be resolved by cat-file -e.
        val e =
            runCatching {
                CoverageAuthorityLoader.resolveBaseSha(repoRoot, "0000000000000000000000000000000000000000")
            }
        assertTrue(e.isFailure, "unresolvable SHA must be a hard failure")
    }

    private fun git(vararg args: String): String =
        ProcessBuilder(listOf("git") + args)
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()
            .let { p ->
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                out
            }
}
