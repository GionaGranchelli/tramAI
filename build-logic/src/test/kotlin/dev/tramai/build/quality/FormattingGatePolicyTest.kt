package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Durable static wiring for the KtLint max-line-length policy (Epic 10.1a).
 *
 * `max_line_length = off` must be effective, because KtLint 1.8.0's ktlint_official style DEFAULTS it
 * to 140: without the explicit setting, an untouched file that merely becomes Spotless ratchet-visible
 * starts failing `standard:max-line-length` on long lines the declared policy allows, and
 * `spotlessKotlinApply` cannot converge on it.
 *
 * This asserts DECLARATION, not runtime behaviour — runtime behaviour is proven by the reproduction
 * recorded in the accompanying PR, and a behavioural check would have to nest Gradle inside
 * :build-logic:test. Its job is to stop the repair silently disappearing: the .editorconfig policy
 * text alone is NOT sufficient, because that file's properties are not all propagated into the
 * KtLint step (see build.gradle.kts). All three pieces must survive together.
 */
class FormattingGatePolicyTest {
    private val root: File =
        generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { File(it, ".editorconfig").isFile && File(it, "build.gradle.kts").isFile }

    @Test
    fun `the max-line-length policy is declared in the root editorconfig`() {
        val editorConfig = File(root, ".editorconfig").readText()
        assertTrue(
            editorConfig.contains("max_line_length = off"),
            ".editorconfig must keep declaring max_line_length = off",
        )
    }

    @Test
    fun `the ktlint step carries the editorconfig path and mirrors the policy`() {
        val build = File(root, "build.gradle.kts").readText()
        assertTrue(
            build.contains("""setEditorConfigPath(rootProject.file(".editorconfig").absolutePath)"""),
            "the ktlint step must keep supplying the root .editorconfig explicitly",
        )
        assertTrue(
            build.contains(""""max_line_length" to "off""""),
            "the ktlint step must keep mirroring max_line_length: setEditorConfigPath alone does not " +
                "propagate it, so removing this override re-arms the 140-character default",
        )
    }
}
