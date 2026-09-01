package dev.tramai.build.supplychain

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * Supply-chain / CycloneDX convention plugin (Epic 9.2d-b2 slice A). Applied
 * to the root project. Owns the SBOM post-processing implementation
 * (prepareCycloneDxBom: copy + SHA-256 digest) that previously lived in the
 * root build script, so the root build remains composition-only.
 *
 * The external org.cyclonedx.bom plugin stays applied at the root; this
 * plugin registers the task that consumes its generated BOM.
 */
class TramaiSupplyChainPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project != project.rootProject) return
        project.tasks.register<PrepareCycloneDxBomTask>("prepareCycloneDxBom") {
            group = "verification"
            description = "Run cyclonedxBom and place the result plus digest under build/supply-chain/sbom/"

            dependsOn("cyclonedxBom")

            sourceBomFile.set(
                project.layout.buildDirectory.file("reports/cyclonedx/bom.json"),
            )
            outputDirectory.set(
                project.layout.buildDirectory.dir("supply-chain/sbom"),
            )
        }
    }
}
