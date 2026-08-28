package dev.tramai.build.publishing

import org.gradle.api.Project

/**
 * Resolved publication metadata for Tramai modules.
 *
 * Defaults mirror the historical root build script exactly — 9.2c moves
 * publication metadata to manifest-derived values deliberately.
 */
data class TramaiPublicationMetadata(
    val projectUrl: String,
    val scmUrl: String,
    val scmConnection: String,
    val scmDeveloperConnection: String,
    val licenseName: String,
    val licenseUrl: String,
    val developerId: String,
    val developerName: String,
    val developerEmail: String,
) {
    companion object {
        fun from(project: Project): TramaiPublicationMetadata {
            fun property(name: String, default: String): String =
                project.providers.gradleProperty(name).orElse(default).get()
            return TramaiPublicationMetadata(
                projectUrl = property("tramaiProjectUrl", "https://github.com/GionaGranchelli/tramAI"),
                scmUrl = property("tramaiScmUrl", "https://github.com/GionaGranchelli/tramAI.git"),
                scmConnection = property("tramaiScmConnection", "scm:git:https://github.com/GionaGranchelli/tramAI.git"),
                scmDeveloperConnection = property("tramaiScmDeveloperConnection", "scm:git:ssh://git@github.com/GionaGranchelli/tramAI.git"),
                licenseName = property("tramaiLicenseName", "Apache-2.0"),
                licenseUrl = property("tramaiLicenseUrl", "https://www.apache.org/licenses/LICENSE-2.0.txt"),
                developerId = property("tramaiDeveloperId", "GionaGranchelli"),
                developerName = property("tramaiDeveloperName", "Giona"),
                developerEmail = property("tramaiDeveloperEmail", "opensource@giona.dev"),
            )
        }
    }
}
