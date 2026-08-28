
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-core"))

    implementation(libs.coroutines.core)
    implementation(libs.jackson.databind)

                testImplementation(testFixtures(project(":tramai-testing")))
    testImplementation(libs.coroutines.test)
}

