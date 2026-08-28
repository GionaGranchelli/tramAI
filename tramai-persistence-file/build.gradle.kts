plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-core"))
    api(project(":tramai-engine"))
    api(project(":tramai-security"))

    implementation(libs.coroutines.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)

    testImplementation(testFixtures(project(":tramai-testing")))
            testImplementation(libs.coroutines.test)
        testImplementation("org.junit.jupiter:junit-jupiter")
}

