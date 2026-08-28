
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-orchestration"))

    implementation(libs.coroutines.core)
    implementation(libs.hikaricp)

            testImplementation(libs.h2database)
    }

