
plugins {
    `java-library`
    id("tramai.test-fixtures")
    id("tramai.testing")
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    api(project(":tramai-core"))
    implementation(project(":tramai-security"))
    testFixturesImplementation(project(":tramai-security"))

    implementation(libs.coroutines.core)
    implementation(libs.kotlin.reflect)

                testImplementation(project(":tramai-structured"))
    testImplementation(testFixtures(project(":tramai-testing")))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.jackson.databind)
}

// Epic 12.1a benchmark harness: forward the deep-lane activation flag
// (-Dtramai.benchmark=true) and run metadata to test worker JVMs. Absent the
// flag nothing is forwarded and benchmark classes stay skipped.
tasks.withType<Test>().configureEach {
    providers.systemProperty("tramai.benchmark").orNull?.let { flag ->
        systemProperty("tramai.benchmark", flag)
        systemProperty(
            "tramai.benchmark.gitSha",
            providers.exec { commandLine("git", "rev-parse", "HEAD") }
                .standardOutput.asText.get().trim(),
        )
        systemProperty(
            "tramai.benchmark.out",
            layout.buildDirectory.dir("reports/benchmark").get().asFile.absolutePath,
        )
    }
}

