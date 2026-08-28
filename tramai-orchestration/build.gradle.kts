
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-core"))

    implementation(libs.coroutines.core)
    implementation(libs.mcp.sdk.client)

    testImplementation(project(":tramai-engine"))
    testImplementation(project(":tramai-testing"))
    testImplementation(testFixtures(project(":tramai-testing")))
                testImplementation(libs.mcp.sdk.server)
    testImplementation(libs.h2database)
}

