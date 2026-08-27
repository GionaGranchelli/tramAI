
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    api(project(":tramai-core"))
    implementation(project(":tramai-orchestration"))

    implementation(libs.opentelemetry.api)

    testImplementation(project(":tramai-engine"))
    testImplementation(project(":tramai-orchestration"))
    testImplementation(project(":tramai-structured"))
    testImplementation(libs.coroutines.core)
    testImplementation(libs.opentelemetry.exporter.otlp)
    testImplementation(libs.opentelemetry.sdk.metrics)
    testImplementation(libs.opentelemetry.sdk.trace)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
}

tasks.test {
    // The runtime-event-catalogue architecture test scans every module's
    // production Kotlin sources repository-wide. Declare those files as task
    // inputs so a literal added to ANY module re-runs the guard (otherwise
    // Gradle marks the task up-to-date and the fail-closed scan never fires).
    // The inputs are MATERIALIZED explicit File instances, not a live
    // FileTree rooted at src/main: src/main contains task outputs (e.g.
    // tramai-dashboard's buildDashboard/npmInstall), and wiring the tree into
    // this task's inputs makes Gradle infer a dependency on those producers.
    rootProject.subprojects.forEach { sub ->
        val mainDir = sub.layout.projectDirectory.dir("src/main").asFile
        if (mainDir.isDirectory) {
            val kotlinFiles = mainDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
            inputs.files(kotlinFiles)
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}
