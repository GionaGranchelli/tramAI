
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    api(project(":tramai-core"))

    implementation(libs.coroutines.core)
    implementation(libs.mcp.sdk.client)

    testImplementation(project(":tramai-engine"))
    testImplementation(project(":tramai-testing"))
    testImplementation(testFixtures(project(":tramai-testing")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.mcp.sdk.server)
    testImplementation(libs.h2database)
}

