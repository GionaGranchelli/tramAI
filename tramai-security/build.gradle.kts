
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-core"))
    implementation(project(":tramai-control-plane"))

    implementation(libs.coroutines.core)

    testImplementation(testFixtures(project(":tramai-testing")))
    testImplementation(testFixtures(project(":tramai-control-plane")))
    testImplementation(libs.coroutines.test)
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation(libs.jackson.databind)
}

