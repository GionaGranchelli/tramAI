
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    api(project(":tramai-core"))

    implementation(libs.coroutines.core)
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(testFixtures(project(":tramai-testing")))
    testImplementation(libs.coroutines.test)
}

