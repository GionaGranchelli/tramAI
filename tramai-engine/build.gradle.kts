
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

