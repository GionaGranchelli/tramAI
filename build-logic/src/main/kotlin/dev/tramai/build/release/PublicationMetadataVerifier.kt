package dev.tramai.build.release

import java.io.File
import org.w3c.dom.Element

/** Expected POM metadata values, resolved by the plugin from Gradle properties. */
data class ExpectedPublicationMetadata(
    val group: String,
    val version: String,
    val projectUrl: String,
    val scmUrl: String,
    val scmConnection: String,
    val scmDeveloperConnection: String,
    val licenseName: String,
    val licenseUrl: String,
    val developerId: String,
    val developerName: String,
    val developerEmail: String,
)

/**
 * Pure verifier for generated Maven POM metadata (9.2b).
 *
 * Checks groupId, artifactId, version, name, description (from the module
 * catalog via the independent [expectedDescriptions] input — 9.2c-c), project
 * URL, license, developer, SCM, normal-module packaging, BOM packaging=pom,
 * and the exact BOM managed artifact set. Every violation throws
 * [IllegalStateException] with a stable diagnostic.
 *
 * The verifier never derives descriptions from the same code path as the
 * publisher: it receives them as a map input, so a broken catalog lookup
 * cannot make both sides agree on the same wrong value.
 */
object PublicationMetadataVerifier {

    fun verify(
        expected: ExpectedPublicationMetadata,
        expectedDescriptions: Map<String, String>,
        publishableModules: List<String>,
        jarPublicationModules: List<String>,
        pomFileFor: (moduleName: String) -> File,
    ) {
        publishableModules.forEach { projectName ->
            val pomFile = pomFileFor(projectName)
            require(pomFile.isFile) { "Missing generated POM for $projectName at ${pomFile.absolutePath}" }

            val project = PomXml.parse(pomFile)
            require(project.directChildText("groupId") == expected.group) { "Unexpected groupId in $projectName POM" }
            require(project.directChildText("artifactId") == projectName) { "Unexpected artifactId in $projectName POM" }
            require(project.directChildText("version") == expected.version) { "Unexpected version in $projectName POM" }
            require(project.directChildText("name") == projectName) { "Unexpected name in $projectName POM" }
            val expectedDescription = requireNotNull(expectedDescriptions[projectName]) {
                "No expected description for $projectName — catalog must provide a non-blank description for every published module"
            }
            require(project.directChildText("description") == expectedDescription) {
                "Unexpected description in $projectName POM"
            }
            require(project.directChildText("url") == expected.projectUrl) { "Unexpected project URL in $projectName POM" }

            val license = requireNotNull(project.directChild("licenses")?.directChild("license")) {
                "Missing license section in $projectName POM"
            }
            require(license.directChildText("name") == expected.licenseName) { "Unexpected license name in $projectName POM" }
            require(license.directChildText("url") == expected.licenseUrl) { "Unexpected license URL in $projectName POM" }

            val developer = requireNotNull(project.directChild("developers")?.directChild("developer")) {
                "Missing developer section in $projectName POM"
            }
            require(developer.directChildText("id") == expected.developerId) { "Unexpected developer id in $projectName POM" }
            require(developer.directChildText("name") == expected.developerName) { "Unexpected developer name in $projectName POM" }
            require(developer.directChildText("email") == expected.developerEmail) { "Unexpected developer email in $projectName POM" }

            val scm = requireNotNull(project.directChild("scm")) { "Missing SCM section in $projectName POM" }
            require(scm.directChildText("url") == expected.scmUrl) { "Unexpected SCM URL in $projectName POM" }
            require(scm.directChildText("connection") == expected.scmConnection) { "Unexpected SCM connection in $projectName POM" }
            require(scm.directChildText("developerConnection") == expected.scmDeveloperConnection) {
                "Unexpected SCM developer connection in $projectName POM"
            }

            val packaging = project.directChildText("packaging")
            if (projectName == "tramai-bom") {
                require(packaging == "pom") { "The BOM must publish as packaging=pom" }
                val dependencyManagement = requireNotNull(project.directChild("dependencyManagement")) {
                    "Missing dependencyManagement section in tramai-bom POM"
                }
                val dependencies = requireNotNull(dependencyManagement.directChild("dependencies")) {
                    "Missing dependencyManagement dependencies in tramai-bom POM"
                }
                val managedArtifactIds = buildList {
                    val children = dependencies.childNodes
                    for (index in 0 until children.length) {
                        val child = children.item(index)
                        if (child is Element && child.tagName == "dependency") {
                            add(child.directChildText("artifactId").orEmpty())
                        }
                    }
                }
                val expectedManagedArtifacts = jarPublicationModules.toSet()
                require(managedArtifactIds.toSet() == expectedManagedArtifacts) {
                    "Unexpected BOM contents. Expected $expectedManagedArtifacts but found ${managedArtifactIds.toSet()}"
                }
            } else {
                require(packaging == null || packaging == "jar") { "Unexpected packaging in $projectName POM: $packaging" }
            }
        }
    }
}
