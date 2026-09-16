package dev.tramai.build.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFailsWith

/**
 * The example guide names the Maven coordinates it consumes, so the release it names must be the
 * last promoted one — derived from CHANGELOG.md, never a literal that goes stale at every release
 * cut. A literal here previously made the full release closure red on the Epic base while every
 * PR-scoped gate stayed green.
 */
class ExampleGuideReleaseAssertionTest {
    @TempDir
    lateinit var tempDir: File

    private val repoRoot: File by lazy {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, ".git").isDirectory) {
            dir = dir.parentFile ?: error("repo root not found from ${System.getProperty("user.dir")}")
        }
        dir
    }

    @Test
    fun `the promoted release is derived from the dated changelog heading`() {
        val dir = fixture(promotedRelease = "1.2.3")

        assertEquals("1.2.3", promotedReleaseVersion(dir))
    }

    @Test
    fun `the real example guide is accepted when it names the promoted release`() {
        val dir = fixture()

        // No exception: the guide names the release the CHANGELOG promotes.
        RootDocGuardVerifiers.exampleSelectionGuide(dir)
    }

    @Test
    fun `the example guide is rejected when it names a release other than the promoted one`() {
        val dir = fixture()
        val guide = File(dir, "examples/README.md")
        val text = guide.readText()
        // Version-agnostic: whatever release the real guide names, naming another must fail.
        guide.writeText(text.replace(Regex("released TramAI \\d+\\.\\d+\\.\\d+"), "released TramAI 0.0.1"))

        val failure = assertFailsWith<IllegalArgumentException> { RootDocGuardVerifiers.exampleSelectionGuide(dir) }

        assertTrue(failure.message.orEmpty().contains("must name the last promoted release"), failure.message.orEmpty())
    }

    /** Real guide + its link targets (the guard resolves them) + a dated CHANGELOG. */
    private fun fixture(promotedRelease: String = promotedReleaseVersion(repoRoot)): File {
        val dir = File(tempDir, "root").apply { mkdirs() }
        File(dir, "CHANGELOG.md").writeText("## Unreleased\n\n## $promotedRelease - 2026-07-06\n")
        File(dir, "settings.gradle.kts").writeText(File(repoRoot, "settings.gradle.kts").readText())
        File(dir, "examples/README.md")
            .apply { parentFile.mkdirs() }
            .writeText(File(repoRoot, "examples/README.md").readText())
        for (target in LINK_TARGETS) {
            File(dir, target).apply { parentFile.mkdirs() }.writeText("# ${target.substringAfterLast('/')}\n")
        }
        return dir
    }

    private companion object {
        val LINK_TARGETS =
            listOf(
                "examples/kotlin-native-smoke-example/README.md",
                "examples/kotlin-springboot-example/README.md",
                "examples/sovereign-lab/README.md",
                "examples/spring-sovereign-starter/README.md",
                "docs/STATUS.md",
                "scripts/verify-zero-egress.sh",
            )
    }
}
