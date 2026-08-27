package dev.tramai.build.publishing

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TestKit discriminator suite for the tramai.publishing convention plugin
 * (Epic 9.2a). Each test builds a minimal fixture in a temp directory and
 * asserts externally observable publishing behavior — no credentials are used
 * in test data.
 *
 * Version is provided via gradle.properties so project.version is set before
 * the convention plugin reacts to java-library/java-platform (the plugin
 * applies during the plugins block).
 */
class TramaiPublishingPluginTest {

    // ── fixture helpers ──────────────────────────────────────────────────────

    private fun writeFile(base: File, relativePath: String, content: String) {
        val target = File(base, relativePath)
        target.parentFile.mkdirs()
        target.writeText(content)
    }

    private fun singleProjectFixture(
        dir: File,
        plugins: String = "java-library",
        probeBody: String = "",
        version: String = "0.6.0",
    ): File {
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "sample"
            """.trimIndent(),
        )
        writeFile(
            dir,
            "gradle.properties",
            """
            version=$version
            group=dev.tramai
            """.trimIndent(),
        )
        writeFile(
            dir,
            "build.gradle.kts",
            """
            import org.gradle.api.artifacts.repositories.MavenArtifactRepository
            import org.gradle.api.publish.maven.MavenPublication

            plugins {
                `$plugins`
                id("tramai.publishing")
            }

            tasks.register("probe") {
                doLast {
                    $probeBody
                }
            }
            """.trimIndent(),
        )
        return dir
    }

    private fun runner(dir: File, vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(*args)

    private fun runProbe(dir: File, task: String = "probe", vararg args: String): Map<String, String> {
        val result = runner(dir, task, "--quiet", *args).build()
        return result.output.lineSequence()
            .filter { it.startsWith("PROBE:") }
            .associate { line ->
                val (key, value) = line.removePrefix("PROBE:").split("=", limit = 2)
                key to value
            }
    }

    private fun probeRepositoriesBody(): String = """
        val repos = publishing.repositories.map { repo ->
            val artifactRepo = repo as MavenArtifactRepository
            artifactRepo.name + "|" + artifactRepo.url
        }.sorted().joinToString(",")
        println("PROBE:repos=" + repos)
    """.trimIndent()

    // ── P1 — java-library publication ────────────────────────────────────────

    @Test
    fun `P1 java-library publication has java component artifactId and javadoc jar`(@TempDir tempDir: File) {
        val dir = File(tempDir, "p1")
        dir.mkdirs()
        singleProjectFixture(
            dir,
            plugins = "java-library",
            probeBody = """
                val pub = publishing.publications.getByName("maven") as MavenPublication
                println("PROBE:pubName=" + pub.name)
                println("PROBE:artifactId=" + pub.artifactId)
                println("PROBE:components=" + components.names.sorted().joinToString(","))
                println("PROBE:hasJavadocJar=" + tasks.names.contains("javadocJar"))
            """.trimIndent(),
        )
        val probe = runProbe(dir)
        assertEquals("maven", probe["pubName"])
        assertEquals("sample", probe["artifactId"])
        assertTrue(probe["components"]!!.split(",").contains("java"), "java component must exist")
        assertEquals("true", probe["hasJavadocJar"], "javadocJar task must exist for java-library")
    }

    // ── P2 — java-platform publication ───────────────────────────────────────

    @Test
    fun `P2 java-platform publication uses javaPlatform component and pom packaging`(@TempDir tempDir: File) {
        val dir = File(tempDir, "p2")
        dir.mkdirs()
        singleProjectFixture(
            dir,
            plugins = "java-platform",
            probeBody = """
                val pub = publishing.publications.getByName("maven") as MavenPublication
                println("PROBE:pubName=" + pub.name)
                println("PROBE:artifactId=" + pub.artifactId)
                println("PROBE:components=" + components.names.sorted().joinToString(","))
            """.trimIndent(),
        )
        val probe = runProbe(dir)
        assertEquals("maven", probe["pubName"])
        assertTrue(probe["components"]!!.split(",").contains("javaPlatform"), "javaPlatform component must exist")

        // POM packaging must be pom for a platform publication.
        runner(dir, "generatePomFileForMavenPublication", "--quiet").build()
        val pomFile = File(dir, "build/publications/maven/pom-default.xml")
        assertTrue(pomFile.isFile, "generated POM must exist")
        val pomText = pomFile.readText()
        assertTrue(pomText.contains("<packaging>pom</packaging>"), "platform POM must declare packaging=pom")
    }

    // ── P3 — release repository selection ────────────────────────────────────

    @Test
    fun `P3 release version selects release URL with snapshot fallback`(@TempDir tempDir: File) {
        val dir = File(tempDir, "p3")
        dir.mkdirs()
        singleProjectFixture(dir, version = "0.6.0", probeBody = probeRepositoriesBody())

        // Both URLs present → release URL wins.
        val both = runProbe(dir, args = arrayOf("-PtramaiPublishReleaseUrl=https://release.example.com/repo", "-PtramaiPublishSnapshotUrl=https://snapshot.example.com/repo"))
        assertEquals("tramaiRemote|https://release.example.com/repo", both["repos"])

        // Only snapshot URL → fallback to snapshot URL for a release version.
        val fallback = runProbe(dir, args = arrayOf("-PtramaiPublishSnapshotUrl=https://snapshot.example.com/repo"))
        assertEquals("tramaiRemote|https://snapshot.example.com/repo", fallback["repos"])

        // Only release URL → release URL.
        val releaseOnly = runProbe(dir, args = arrayOf("-PtramaiPublishReleaseUrl=https://release.example.com/repo"))
        assertEquals("tramaiRemote|https://release.example.com/repo", releaseOnly["repos"])
    }

    // ── P4 — snapshot repository selection ───────────────────────────────────

    @Test
    fun `P4 snapshot version selects snapshot URL with release fallback`(@TempDir tempDir: File) {
        val dir = File(tempDir, "p4")
        dir.mkdirs()
        singleProjectFixture(dir, version = "0.6.1-SNAPSHOT", probeBody = probeRepositoriesBody())

        // Both URLs present → snapshot URL wins.
        val both = runProbe(dir, args = arrayOf("-PtramaiPublishReleaseUrl=https://release.example.com/repo", "-PtramaiPublishSnapshotUrl=https://snapshot.example.com/repo"))
        assertEquals("tramaiRemote|https://snapshot.example.com/repo", both["repos"])

        // Only release URL → fallback to release URL for a snapshot version.
        val fallback = runProbe(dir, args = arrayOf("-PtramaiPublishReleaseUrl=https://release.example.com/repo"))
        assertEquals("tramaiRemote|https://release.example.com/repo", fallback["repos"])

        // Only snapshot URL → snapshot URL.
        val snapshotOnly = runProbe(dir, args = arrayOf("-PtramaiPublishSnapshotUrl=https://snapshot.example.com/repo"))
        assertEquals("tramaiRemote|https://snapshot.example.com/repo", snapshotOnly["repos"])
    }

    // ── P5 — no repository configured when both URLs absent ──────────────────

    @Test
    fun `P5 no remote repository is configured when both URLs are absent`(@TempDir tempDir: File) {
        val dir = File(tempDir, "p5")
        dir.mkdirs()
        singleProjectFixture(
            dir,
            probeBody = """
                println("PROBE:repos=" + publishing.repositories.map { it.name }.sorted().joinToString(","))
            """.trimIndent(),
        )
        val probe = runProbe(dir)
        assertEquals("", probe["repos"] ?: "", "no repositories must be configured on a developer machine")
    }

    // ── P6 — file repository never receives credentials ──────────────────────

    @Test
    fun `P6 file repository is configured without credentials even when properties are provided`(@TempDir tempDir: File) {
        val dir = File(tempDir, "p6")
        dir.mkdirs()
        singleProjectFixture(
            dir,
            version = "0.6.0",
            probeBody = """
                val repo = publishing.repositories.getByName("tramaiRemote") as MavenArtifactRepository
                println("PROBE:repoUrl=" + repo.url)
                println("PROBE:credUser=" + (repo.credentials.username ?: "NULL"))
                println("PROBE:credPass=" + (repo.credentials.password ?: "NULL"))
            """.trimIndent(),
        )
        val probe = runProbe(
            dir,
            args = arrayOf(
                "-PtramaiPublishReleaseUrl=file:///tmp/repo",
                "-PtramaiPublishUsername=secret-user",
                "-PtramaiPublishPassword=secret-pass",
            ),
        )
        assertEquals("file:/tmp/repo", probe["repoUrl"], "file repository URL must remain file-based")
        assertEquals("NULL", probe["credUser"], "file repository must never receive credentials")
        assertEquals("NULL", probe["credPass"], "file repository must never receive credentials")
    }

    // ── P7 — signing is optional ─────────────────────────────────────────────

    @Test
    fun `P7 configuration succeeds without signing keys and adds signing tasks with keys`(@TempDir tempDir: File) {
        // Case 1: no keys → configuration succeeds, no signing tasks.
        val noKeys = File(tempDir, "p7-nokeys")
        noKeys.mkdirs()
        singleProjectFixture(
            noKeys,
            probeBody = """
                println("PROBE:signTasks=" + tasks.names.filter { it.contains("sign", ignoreCase = true) }.sorted().joinToString(","))
            """.trimIndent(),
        )
        val noKeysProbe = runProbe(noKeys)
        assertFalse(noKeysProbe["signTasks"]!!.contains("Sign"), "no signing tasks must exist without signing material")

        // Case 2: keys present → signing tasks exist. No real credentials in test data.
        val withKeys = File(tempDir, "p7-keys")
        withKeys.mkdirs()
        singleProjectFixture(
            withKeys,
            probeBody = """
                println("PROBE:signTasks=" + tasks.names.filter { it.contains("sign", ignoreCase = true) }.sorted().joinToString(","))
            """.trimIndent(),
        )
        val withKeysProbe = runProbe(withKeys, args = arrayOf("-PsigningKey=test-key", "-PsigningPassword=test-password"))
        assertTrue(withKeysProbe["signTasks"]!!.contains("signMavenPublication"), "signing tasks must exist when keys are provided")
    }

    // ── P8 — sovereign local repository safety ───────────────────────────────

    @Test
    fun `P8 sovereignBundleLocal exists as file repository on selected projects only`(@TempDir tempDir: File) {
        val dir = File(tempDir, "p8")
        dir.mkdirs()
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "fixture"
            include("tramai-core")
            include("tramai-spring")
            """.trimIndent(),
        )
        writeFile(
            dir,
            "build.gradle.kts",
            """
            extra["tramai.publishableModulePaths"] = listOf(":tramai-core", ":tramai-spring")
            """.trimIndent(),
        )
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            import org.gradle.api.artifacts.repositories.MavenArtifactRepository

            plugins {
                `java-library`
                id("tramai.publishing")
            }
            group = "dev.tramai"
            version = "0.6.0"
            tasks.register("probe") {
                doLast {
                    val repos = publishing.repositories.map { repo ->
                        val artifactRepo = repo as MavenArtifactRepository
                        artifactRepo.name + "|" + artifactRepo.url
                    }.sorted().joinToString(",")
                    println("PROBE:repos=" + repos)
                    if (publishing.repositories.names.contains("sovereignBundleLocal")) {
                        val repo = publishing.repositories.getByName("sovereignBundleLocal") as MavenArtifactRepository
                        println("PROBE:sovereignCredUser=" + (repo.credentials.username ?: "NULL"))
                    }
                }
            }
            """.trimIndent(),
        )
        writeFile(
            dir,
            "tramai-spring/build.gradle.kts",
            """
            plugins {
                `java-library`
                id("tramai.publishing")
            }
            group = "dev.tramai"
            version = "0.6.0"
            tasks.register("probe") {
                doLast {
                    println("PROBE:repos=" + publishing.repositories.map { it.name }.sorted().joinToString(","))
                }
            }
            """.trimIndent(),
        )

        // tramai-core is in the sovereign bundle → sovereignBundleLocal exists, file://, no credentials.
        val coreProbe = runProbe(dir, task = ":tramai-core:probe")
        assertTrue(coreProbe["repos"]!!.contains("sovereignBundleLocal|file:"), "selected project must get the file-based sovereign repo")
        assertEquals("NULL", coreProbe["sovereignCredUser"], "sovereign repo must never receive credentials")

        // tramai-spring is excluded from the sovereign bundle → no sovereignBundleLocal.
        val springProbe = runProbe(dir, task = ":tramai-spring:probe")
        assertFalse(springProbe["repos"]!!.contains("sovereignBundleLocal"), "non-selected project must not get the sovereign repo")
    }

    // ── P9 — publication metadata parity ─────────────────────────────────────

    @Test
    fun `P9 generated POM carries unchanged publication metadata`(@TempDir tempDir: File) {
        val dir = File(tempDir, "p9")
        dir.mkdirs()
        singleProjectFixture(
            dir,
            plugins = "java-library",
            probeBody = """ println("PROBE:done=true") """.trimIndent(),
        )
        runner(dir, "generatePomFileForMavenPublication", "--quiet").build()
        val pomFile = File(dir, "build/publications/maven/pom-default.xml")
        assertTrue(pomFile.isFile, "generated POM must exist")
        val pom = pomFile.readText()

        // group / artifactId / version / name / description / URL / license / developer / SCM / packaging
        assertTrue(pom.contains("<groupId>dev.tramai</groupId>"), "POM must carry groupId")
        assertTrue(pom.contains("<artifactId>sample</artifactId>"), "POM must carry artifactId")
        assertTrue(pom.contains("<version>0.6.0</version>"), "POM must carry version")
        assertTrue(pom.contains("<name>sample</name>"), "POM must carry name")
        assertTrue(pom.contains("<description>Tramai module sample.</description>"), "POM must carry compatibility description")
        assertTrue(pom.contains("<url>https://github.com/GionaGranchelli/tramAI</url>"), "POM must carry project URL")
        assertTrue(pom.contains("<name>Apache-2.0</name>"), "POM must carry license name")
        assertTrue(pom.contains("<id>GionaGranchelli</id>"), "POM must carry developer id")
        assertTrue(pom.contains("<connection>scm:git:https://github.com/GionaGranchelli/tramAI.git</connection>"), "POM must carry SCM")
        assertFalse(pom.contains("<packaging>pom</packaging>"), "library POM must not be pom-packaged")
    }

    // ── P10 — configuration cache at the convention boundary ─────────────────

    @Test
    fun `P10 configuration cache is reused on second run`(@TempDir tempDir: File) {
        val dir = File(tempDir, "p10")
        dir.mkdirs()
        singleProjectFixture(dir, plugins = "java-library")

        runner(dir, "help", "--configuration-cache").build()
        val second = runner(dir, "help", "--configuration-cache").build()
        assertTrue(
            second.output.contains("Reusing configuration cache"),
            "second run must reuse the configuration cache. Output: ${second.output.takeLast(500)}",
        )
    }

    // ── S1 — structural guard: publishing stays out of the root script ───────

    @Test
    fun `S1 root build script contains no publishing implementation`() {
        val repositoryRoot = System.getProperty("tramai.repositoryRoot")
            ?: error("tramai.repositoryRoot system property must be set")
        val rootBuildScript = File(repositoryRoot, "build.gradle.kts")
        assertTrue(rootBuildScript.isFile, "root build.gradle.kts must exist at $repositoryRoot")
        val text = rootBuildScript.readText()

        val forbidden = listOf(
            "configureTramaiPublishing",
            "configureSovereignBundleLocalRepo",
            "import org.gradle.api.publish.PublishingExtension",
            "import org.gradle.api.publish.maven.MavenPublication",
            "import org.gradle.plugins.signing.SigningExtension",
        )
        forbidden.forEach { identifier ->
            assertFalse(text.contains(identifier), "root build.gradle.kts must not contain $identifier")
        }
    }
}
