
plugins {
    `java-library`
    id("tramai.test-fixtures")
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    api(project(":tramai-core"))
    implementation(project(":tramai-security"))
    testFixturesImplementation(project(":tramai-security"))

    implementation(libs.coroutines.core)
    implementation(libs.kotlin.reflect)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(project(":tramai-structured"))
    testImplementation(testFixtures(project(":tramai-testing")))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.jackson.databind)
}

