package dev.tramai.build.sovereign

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MavenPublishedArtifactResolverTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `literal release file resolves`() {
        val moduleDir = File(tempDir, "tramai-core/0.5.0").apply { mkdirs() }
        val pom = File(moduleDir, "tramai-core-0.5.0.pom").apply { writeText("<project/>") }
        assertEquals(
            pom,
            MavenPublishedArtifactResolver.resolve(moduleDir, "tramai-core", "0.5.0", "pom"),
        )
    }

    @Test
    fun `unique snapshot via metadata resolves`() {
        val moduleDir = File(tempDir, "tramai-core/0.5.0-SNAPSHOT").apply { mkdirs() }
        File(moduleDir, "maven-metadata.xml").writeText(
            """
            <metadata>
              <versioning>
                <snapshotVersions>
                  <snapshotVersion>
                    <extension>pom</extension>
                    <value>0.5.0-20240801.120000-3</value>
                  </snapshotVersion>
                  <snapshotVersion>
                    <extension>jar</extension>
                    <value>0.5.0-20240801.120000-3</value>
                  </snapshotVersion>
                </snapshotVersions>
              </versioning>
            </metadata>
            """.trimIndent(),
        )
        val expected = File(moduleDir, "tramai-core-0.5.0-20240801.120000-3.pom").apply { writeText("<project/>") }
        assertEquals(
            expected,
            MavenPublishedArtifactResolver.resolve(moduleDir, "tramai-core", "0.5.0-SNAPSHOT", "pom"),
        )
    }

    @Test
    fun `directory scan fallback resolves`() {
        val moduleDir = File(tempDir, "tramai-core/0.5.0-SNAPSHOT").apply { mkdirs() }
        File(moduleDir, "maven-metadata.xml").writeText("<metadata><versioning><snapshotVersions/></versioning></metadata>")
        val expected = File(moduleDir, "tramai-core-0.5.0-SNAPSHOT.pom").apply { writeText("<project/>") }
        assertEquals(
            expected,
            MavenPublishedArtifactResolver.resolve(moduleDir, "tramai-core", "0.5.0-SNAPSHOT", "pom"),
        )
    }

    @Test
    fun `pretty-printed multiline metadata resolves unique snapshot exactly`() {
        // Real Maven metadata is pretty-printed across many lines — the old
        // regex (`.` does not match `\n`) silently bypassed metadata and fell
        // back to an unsorted directory scan. XML parsing must win here.
        val moduleDir = File(tempDir, "tramai-core/0.5.0-SNAPSHOT").apply { mkdirs() }
        File(moduleDir, "maven-metadata.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata modelVersion="1.1.0">
              <groupId>dev.tramai</groupId>
              <artifactId>tramai-core</artifactId>
              <version>0.5.0-SNAPSHOT</version>
              <versioning>
                <snapshot>
                  <timestamp>20240801.120000</timestamp>
                  <buildNumber>3</buildNumber>
                </snapshot>
                <lastUpdated>20240801120000</lastUpdated>
                <snapshotVersions>
                  <snapshotVersion>
                    <classifier>sources</classifier>
                    <extension>jar</extension>
                    <value>0.5.0-20240801.120000-3</value>
                    <updated>20240801120000</updated>
                  </snapshotVersion>
                  <snapshotVersion>
                    <extension>jar</extension>
                    <value>0.5.0-20240801.120000-3</value>
                    <updated>20240801120000</updated>
                  </snapshotVersion>
                  <snapshotVersion>
                    <extension>pom</extension>
                    <value>0.5.0-20240801.120000-3</value>
                    <updated>20240801120000</updated>
                  </snapshotVersion>
                </snapshotVersions>
              </versioning>
            </metadata>
            """.trimIndent(),
        )
        val expected = File(moduleDir, "tramai-core-0.5.0-20240801.120000-3.pom").apply { writeText("<project/>") }
        assertEquals(
            expected,
            MavenPublishedArtifactResolver.resolve(moduleDir, "tramai-core", "0.5.0-SNAPSHOT", "pom"),
        )
    }

    @Test
    fun `multiple directory candidates fail closed when metadata cannot resolve`() {
        val moduleDir = File(tempDir, "tramai-core/0.5.0-SNAPSHOT").apply { mkdirs() }
        File(moduleDir, "maven-metadata.xml").writeText("<metadata><versioning><snapshotVersions/></versioning></metadata>")
        File(moduleDir, "tramai-core-0.5.0-20240801.120000-3.pom").writeText("<project/>")
        File(moduleDir, "tramai-core-0.5.0-20240802.090000-4.pom").writeText("<project/>")
        val e = assertFailsWith<IllegalStateException> {
            MavenPublishedArtifactResolver.resolve(moduleDir, "tramai-core", "0.5.0-SNAPSHOT", "pom")
        }
        assertTrue(e.message!!.contains("Ambiguous"), "expected ambiguity failure, got: ${e.message}")
    }

    @Test
    fun `missing artifact returns non-existent literal path`() {
        val moduleDir = File(tempDir, "tramai-core/0.5.0").apply { mkdirs() }
        val resolved = MavenPublishedArtifactResolver.resolve(moduleDir, "tramai-core", "0.5.0", "jar")
        assertFalse(resolved.isFile)
        assertEquals("tramai-core-0.5.0.jar", resolved.name)
    }
}
