package dev.tramai.build.sovereign

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun `missing artifact returns non-existent literal path`() {
        val moduleDir = File(tempDir, "tramai-core/0.5.0").apply { mkdirs() }
        val resolved = MavenPublishedArtifactResolver.resolve(moduleDir, "tramai-core", "0.5.0", "jar")
        assertFalse(resolved.isFile)
        assertEquals("tramai-core-0.5.0.jar", resolved.name)
    }
}
