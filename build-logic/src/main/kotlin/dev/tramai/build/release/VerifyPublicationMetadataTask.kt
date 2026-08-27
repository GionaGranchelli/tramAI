package dev.tramai.build.release

import org.gradle.api.DefaultTask
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Typed release verification task: verifies generated Maven POM metadata for
 * every publishable Tramai module (9.2b extraction). Pure verification — no
 * output artifact, so build caching is intentionally disabled.
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class VerifyPublicationMetadataTask : DefaultTask() {

    @get:Input
    abstract val expectedGroup: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val expectedVersion: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val expectedProjectUrl: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val expectedScmUrl: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val expectedScmConnection: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val expectedScmDeveloperConnection: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val expectedLicenseName: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val expectedLicenseUrl: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val expectedDeveloperId: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val expectedDeveloperName: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val expectedDeveloperEmail: org.gradle.api.provider.Property<String>

    @get:Input
    abstract val publishableModules: org.gradle.api.provider.ListProperty<String>

    @get:Input
    abstract val jarPublicationModules: org.gradle.api.provider.ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pomFiles: org.gradle.api.file.ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val publishable = publishableModules.get()
        val jarModules = jarPublicationModules.get()
        val expected = ExpectedPublicationMetadata(
            group = expectedGroup.get(),
            version = expectedVersion.get(),
            projectUrl = expectedProjectUrl.get(),
            scmUrl = expectedScmUrl.get(),
            scmConnection = expectedScmConnection.get(),
            scmDeveloperConnection = expectedScmDeveloperConnection.get(),
            licenseName = expectedLicenseName.get(),
            licenseUrl = expectedLicenseUrl.get(),
            developerId = expectedDeveloperId.get(),
            developerName = expectedDeveloperName.get(),
            developerEmail = expectedDeveloperEmail.get(),
        )

        PublicationMetadataVerifier.verify(
            expected = expected,
            publishableModules = publishable,
            jarPublicationModules = jarModules,
            pomFileFor = { moduleName ->
                pomFiles.files.firstOrNull { it.path.endsWith("$moduleName/build/publications/maven/pom-default.xml") }
                    ?: File("$moduleName/build/publications/maven/pom-default.xml")
            },
        )
    }
}
