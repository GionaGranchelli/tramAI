package dev.tramai.build.release

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublicationMetadataVerifierTest {

    @TempDir
    lateinit var tempDir: File

    private val expected = ExpectedPublicationMetadata(
        group = "dev.tramai",
        version = "0.5.0",
        projectUrl = "https://github.com/GionaGranchelli/tramAI",
        scmUrl = "https://github.com/GionaGranchelli/tramAI.git",
        scmConnection = "scm:git:https://github.com/GionaGranchelli/tramAI.git",
        scmDeveloperConnection = "scm:git:ssh://git@github.com/GionaGranchelli/tramAI.git",
        licenseName = "Apache-2.0",
        licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
        developerId = "GionaGranchelli",
        developerName = "Giona",
        developerEmail = "opensource@giona.dev",
    )

    private val publishableModules = listOf("tramai-core", "tramai-bom")
    private val jarPublicationModules = listOf("tramai-core")

    private val expectedDescriptions = mapOf(
        "tramai-core" to "Core annotations, request models, provider registry, and exception types for Tramai.",
        "tramai-bom" to "Bill of materials for aligning Tramai module versions.",
    )

    private fun writePom(
        moduleName: String = "tramai-core",
        overrides: Map<String, String?> = emptyMap(),
    ): File {
        fun field(name: String, default: String): String = overrides[name] ?: default
        val isBom = moduleName == "tramai-bom"
        val packagingElement = overrides["packaging"]?.let { "<packaging>$it</packaging>" }
            ?: if (isBom) "<packaging>pom</packaging>" else ""
        val dependencyManagement = if (isBom || overrides["bom"] == "true") {
            """
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>dev.tramai</groupId>
                    <artifactId>tramai-core</artifactId>
                    <version>0.5.0</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            """.trimIndent()
        } else ""
        val defaultDescription = if (moduleName == "tramai-bom") {
            "Bill of materials for aligning Tramai module versions."
        } else {
            "Core annotations, request models, provider registry, and exception types for Tramai."
        }
        val pom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <groupId>${field("groupId", "dev.tramai")}</groupId>
              <artifactId>${field("artifactId", moduleName)}</artifactId>
              <version>${field("version", "0.5.0")}</version>
              <name>${field("name", moduleName)}</name>
              <description>${field("description", defaultDescription)}</description>
              <url>${field("url", "https://github.com/GionaGranchelli/tramAI")}</url>
              $packagingElement
              <licenses>
                <license>
                  <name>${field("licenseName", "Apache-2.0")}</name>
                  <url>${field("licenseUrl", "https://www.apache.org/licenses/LICENSE-2.0.txt")}</url>
                  <distribution>repo</distribution>
                </license>
              </licenses>
              <developers>
                <developer>
                  <id>${field("developerId", "GionaGranchelli")}</id>
                  <name>${field("developerName", "Giona")}</name>
                  <email>${field("developerEmail", "opensource@giona.dev")}</email>
                </developer>
              </developers>
              <scm>
                <url>${field("scmUrl", "https://github.com/GionaGranchelli/tramAI.git")}</url>
                <connection>${field("scmConnection", "scm:git:https://github.com/GionaGranchelli/tramAI.git")}</connection>
                <developerConnection>${field("scmDeveloperConnection", "scm:git:ssh://git@github.com/GionaGranchelli/tramAI.git")}</developerConnection>
              </scm>
              $dependencyManagement
            </project>
        """.trimIndent()
        val file = File(tempDir, "pom-$moduleName.xml")
        // trimStart(): the XML declaration must be the very first content. The
        // interpolated dependencyManagement block has 0-indent lines, so Kotlin's
        // trimIndent() cannot normalise the template's own indentation for BOMs.
        file.writeText(pom.trimStart())
        return file
    }

    private fun verify(
        publishable: List<String> = publishableModules,
        jarPublishing: List<String> = jarPublicationModules,
        descriptions: Map<String, String> = expectedDescriptions,
        pomFor: (String) -> File = { name -> writePom(name) },
    ) {
        PublicationMetadataVerifier.verify(
            expected = expected,
            expectedDescriptions = descriptions,
            publishableModules = publishable,
            jarPublicationModules = jarPublishing,
            pomFileFor = pomFor,
        )
    }

    @Test
    fun `valid POMs pass`() {
        verify()
    }

    @Test
    fun `wrong group fails`() {
        val e = assertFailsWith<IllegalArgumentException> {
            verify(pomFor = { _ -> writePom(overrides = mapOf("groupId" to "com.wrong")) })
        }
        assertTrue(e.message!!.contains("groupId"))
    }

    @Test
    fun `wrong artifact fails`() {
        val e = assertFailsWith<IllegalArgumentException> {
            verify(pomFor = { _ -> writePom(overrides = mapOf("artifactId" to "tramai-other")) })
        }
        assertTrue(e.message!!.contains("artifactId"))
    }

    @Test
    fun `wrong version fails`() {
        val e = assertFailsWith<IllegalArgumentException> {
            verify(pomFor = { _ -> writePom(overrides = mapOf("version" to "0.4.0")) })
        }
        assertTrue(e.message!!.contains("version"))
    }

    @Test
    fun `wrong license fails`() {
        val e = assertFailsWith<IllegalArgumentException> {
            verify(pomFor = { _ -> writePom(overrides = mapOf("licenseName" to "MIT")) })
        }
        assertTrue(e.message!!.contains("license"))
    }

    @Test
    fun `wrong SCM fails`() {
        val e = assertFailsWith<IllegalArgumentException> {
            verify(pomFor = { _ -> writePom(overrides = mapOf("scmUrl" to "https://example.com/wrong.git")) })
        }
        assertTrue(e.message!!.contains("SCM"))
    }

    @Test
    fun `wrong developer fails`() {
        val e = assertFailsWith<IllegalArgumentException> {
            verify(pomFor = { _ -> writePom(overrides = mapOf("developerName" to "Someone Else")) })
        }
        assertTrue(e.message!!.contains("developer"))
    }

    @Test
    fun `wrong description fails`() {
        // D7 — verifier is independent: POM description != expectedDescriptions[module] must fail
        val e = assertFailsWith<IllegalArgumentException> {
            verify(pomFor = { _ ->
                writePom(overrides = mapOf("description" to "Some other description."))
            })
        }
        assertTrue(e.message!!.contains("description"), "message was: ${e.message}")
    }

    @Test
    fun `missing expected description fails closed`() {
        // D8 — no compatibility fallback: a publishable module with no catalog
        // description must fail, never fall back to a synthesized value.
        val e = assertFailsWith<IllegalArgumentException> {
            verify(descriptions = mapOf("tramai-bom" to "Bill of materials for aligning Tramai module versions."))
        }
        assertTrue(e.message!!.contains("No expected description"), "message was: ${e.message}")
    }

    @Test
    fun `wrong packaging fails`() {
        val e = assertFailsWith<IllegalArgumentException> {
            verify(pomFor = { _ -> writePom(overrides = mapOf("packaging" to "war")) })
        }
        assertTrue(e.message!!.contains("packaging"))
    }

    @Test
    fun `wrong BOM membership fails`() {
        // BOM POM that manages an artifact outside the expected jar publication set
        val bomPom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <groupId>dev.tramai</groupId>
              <artifactId>tramai-bom</artifactId>
              <version>0.5.0</version>
              <packaging>pom</packaging>
              <name>tramai-bom</name>
              <description>Bill of materials for aligning Tramai module versions.</description>
              <url>https://github.com/GionaGranchelli/tramAI</url>
              <licenses>
                <license>
                  <name>Apache-2.0</name>
                  <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
                  <distribution>repo</distribution>
                </license>
              </licenses>
              <developers>
                <developer>
                  <id>GionaGranchelli</id>
                  <name>Giona</name>
                  <email>opensource@giona.dev</email>
                </developer>
              </developers>
              <scm>
                <url>https://github.com/GionaGranchelli/tramAI.git</url>
                <connection>scm:git:https://github.com/GionaGranchelli/tramAI.git</connection>
                <developerConnection>scm:git:ssh://git@github.com/GionaGranchelli/tramAI.git</developerConnection>
              </scm>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>dev.tramai</groupId>
                    <artifactId>tramai-unexpected</artifactId>
                    <version>0.5.0</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
        """.trimIndent()
        val bomFile = File(tempDir, "bom-pom.xml")
        bomFile.writeText(bomPom.trimStart())

        val e = assertFailsWith<IllegalArgumentException> {
            verify(pomFor = { name -> if (name == "tramai-bom") bomFile else writePom() })
        }
        assertTrue(e.message!!.contains("BOM"), "message was: ${e.message}")
    }

    @Test
    fun `missing pom fails`() {
        val e = assertFailsWith<IllegalArgumentException> {
            verify(pomFor = { _ -> File(tempDir, "does-not-exist.xml") })
        }
        assertTrue(e.message!!.contains("Missing"))
    }
}
