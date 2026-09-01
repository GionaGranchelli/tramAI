package dev.tramai.build.supplychain

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.security.MessageDigest

/**
 * Runs cyclonedxBom and places the result plus digest under
 * build/supply-chain/sbom/ (Epic 9.2d-b2 slice A). Moved verbatim from the
 * root build script into the supply-chain convention plugin. The action
 * consumes the declared source-BOM property rather than rediscovering the
 * historical root path.
 *
 * The CycloneDX plugin itself (org.cyclonedx.bom) stays applied at the root;
 * this task is the post-processing owner (copy + SHA-256 digest).
 */
@DisableCachingByDefault(because = "Verification/supply-chain task reads the generated BOM and writes a digest")
abstract class PrepareCycloneDxBomTask : DefaultTask() {
    /**
     * Generated CycloneDX BOM: build/reports/cyclonedx/bom.json.
     *
     * [Internal]: the historical root closure performed NO input validation and
     * self-checked existence with its own warn-and-skip diagnostic. Declaring
     * this as an [org.gradle.api.tasks.InputFile] (even Optional) makes Gradle
     * fail validation when the file is absent, replacing the historical
     * fail-soft contract with a generic error — exactly what the a3b2a typed
     * tasks avoid. The action keeps the historical existence check.
     */
    @get:Internal
    abstract val sourceBomFile: RegularFileProperty

    /** Output directory: build/supply-chain/sbom/. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        // The source BOM is deliberately @Internal (historical fail-soft
        // warn-and-skip contract; @InputFile/@Optional fails Gradle validation
        // on an absent file). Without a tracked input, Gradle's up-to-date
        // check would skip this task once the output exists, even when
        // cyclonedxBom regenerates the BOM — the historical root closure
        // always re-ran. Force execution to preserve that contract.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun prepare() {
        val sbomDir = outputDirectory.get().asFile
        sbomDir.mkdirs()
        val sourceBom = sourceBomFile.get().asFile
        val targetBom = sbomDir.resolve("tramai-cyclonedx-sbom.json")
        if (sourceBom.exists()) {
            sourceBom.copyTo(targetBom, overwrite = true)
            val digest = MessageDigest.getInstance("SHA-256")
            val hex =
                digest
                    .digest(targetBom.readBytes())
                    .joinToString("") { "%02x".format(it) }
            sbomDir
                .resolve("tramai-cyclonedx-sbom.sha256")
                .writeText("sha256:$hex")
            logger.lifecycle("SBOM generated: ${targetBom.absolutePath}")
            logger.lifecycle("SBOM digest: build/supply-chain/sbom/tramai-cyclonedx-sbom.sha256")
        } else {
            logger.warn("cyclonedxBom did not produce reports/cyclonedx/bom.json in the build directory; skipping SBOM copy.")
        }
    }
}
