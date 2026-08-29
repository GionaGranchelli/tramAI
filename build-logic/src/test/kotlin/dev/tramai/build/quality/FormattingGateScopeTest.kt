package dev.tramai.build.quality

import org.junit.jupiter.api.Test

/**
 * Durable scope tests for the Epic 10.1a incremental Kotlin formatting gate:
 * build-logic source coverage and build-output exclusion. A future edit that drops
 * coverage or broadens an exclusion fails this suite. See
 * [FormattingGateContractTestBase].
 */
class FormattingGateScopeTest : FormattingGateContractTestBase() {
    @Test
    fun `build-logic Kotlin source is covered`() {
        writeKt(
            "build-logic/src/main/kotlin/dev/tramai/build/ZzBadBuildLogic.kt",
            "package dev.tramai.build\nfun   badBuildLogic( ) =4\n",
        )
        commit("bad build-logic kt")
        assertFails(spotless("-PtramaiFormattingBaseRef=origin/master"), "malformed build-logic source")
    }

    @Test
    fun `build-output paths are excluded`() {
        // Base captured BEFORE the generated file exists, so the generated file
        // IS in the delta — the target exclusion is what keeps the gate green.
        val base = head()
        writeKt("tramai-core/build/generated/ZzGen.kt", "package generated\nfun   gen( ) =5\n")
        git(worktree, "add", "-f", "tramai-core/build/generated/ZzGen.kt")
        git(worktree, "commit", "-qm", "malformed kt under build/")
        assertPasses(spotless("-PtramaiFormattingBaseRef=$base"), "generated/build output ignored")
    }
}
