package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Durable ratchet-semantics tests for the Epic 10.1a incremental Kotlin formatting gate.
 *
 * These are the permanent negative properties of 10.1a: a future edit that bypasses
 * the ratchet, ignores the base property, or forces a repository-wide migration fails
 * this suite. See [FormattingGateContractTestBase] for the disposable-worktree setup.
 */
class FormattingGateContractTest : FormattingGateContractTestBase() {
    @Test
    fun `changed malformed Kotlin fails and apply repairs it`() {
        writeKt(moduleKtPath("ZzBad"), malformedKt())
        commit("add malformed kt")
        // origin/master = the pristine repo default branch; the new bad file is
        // the only .kt in the delta.
        assertFails(spotless("-PtramaiFormattingBaseRef=origin/master"), "changed malformed Kotlin")

        val apply =
            gradle(
                "--no-build-cache",
                "spotlessApply",
                "-PtramaiFormattingBaseRef=origin/master",
            )
        assertPasses(apply, "spotlessApply")
        assertTrue(
            File(worktree, moduleKtPath("ZzBad")).readText().contains("fun zzBad() = 1"),
            "spotlessApply must repair the file",
        )
        assertPasses(spotless("-PtramaiFormattingBaseRef=origin/master"), "repaired file")
    }

    @Test
    fun `untouched malformed legacy Kotlin passes and touching it fails`() {
        writeKt(moduleKtPath("Legacy"), malformedKt())
        commit("legacy unformatted at base")
        val legacyBase = head()
        assertPasses(spotless("-PtramaiFormattingBaseRef=$legacyBase"), "untouched legacy debt")

        writeKt(moduleKtPath("Legacy"), malformedKt() + "fun   touched( ) =3\n")
        commit("touch legacy file")
        assertFails(spotless("-PtramaiFormattingBaseRef=$legacyBase"), "touched legacy file")
    }

    @Test
    fun `supplied base ref is authoritative`() {
        writeKt(moduleKtPath("Value"), formattedKt())
        commit("formatted base A")
        val baseA = head()
        writeKt(moduleKtPath("Value"), "package dev.tramai.core\nfun  value( ) =1\n")
        commit("malformed B")
        val baseB = head()
        assertFails(spotless("-PtramaiFormattingBaseRef=$baseA"), "base A inspects B's change")
        assertPasses(spotless("-PtramaiFormattingBaseRef=$baseB"), "base B sees no delta")
    }
}
