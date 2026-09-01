package dev.tramai.build.sovereign

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract: `verifySovereignReleaseManifest` is a PURE verifier — it must
 * validate an existing manifest/artifact bundle WITHOUT executing
 * `prepareSovereignReleaseArtifacts` (which rebuilds release JARs).
 *
 * This is what lets ci.yml's zero-egress job verify artifacts downloaded
 * from artifact-prep without re-running the producer graph (~4m of
 * duplicated work). Any aggregate that needs the artifacts produced must
 * declare `prepareSovereignReleaseArtifacts` explicitly (the RC chain does).
 */
class VerifySovereignReleaseManifestIsPureTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `verifySovereignReleaseManifest does not depend on prepareSovereignReleaseArtifacts`() {
        val dir = File(tempDir, "fixture").apply { mkdirs() }
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"sovereign-fixture\"\n")
        File(dir, "build.gradle.kts").writeText("plugins { id(\"tramai.sovereign-verification\") }\n")

        val result =
            GradleRunner
                .create()
                .withProjectDir(dir)
                .withGradleVersion("9.0.0")
                .withArguments("verifySovereignReleaseManifest", "--dry-run", "--no-build-cache")
                .withPluginClasspath()
                .build()

        // The verifier itself must be planned...
        assertTrue(
            result.output.contains("verifySovereignReleaseManifest"),
            "verifySovereignReleaseManifest must be in the task graph",
        )
        // ...but the producer graph must NOT be pulled in.
        assertFalse(
            result.output.contains("prepareSovereignReleaseArtifacts"),
            "pure verifier must not execute prepareSovereignReleaseArtifacts; " +
                "task graph was:\n${result.output}",
        )
    }
}
