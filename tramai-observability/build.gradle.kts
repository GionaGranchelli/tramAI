
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-core"))
    api(project(":tramai-security"))
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

    // 0.7.4f: end-to-end proof that a persisted projection reaches an exported span through the public query
    // port. The seeder is test-only because the projection writer is internal to :tramai-security, and the
    // reverse edge (:tramai-security -> :tramai-observability) would be a cycle. Test scope only.
    testImplementation(testFixtures(project(":tramai-security")))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.postgresql)
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
