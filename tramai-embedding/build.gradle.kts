
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    implementation(project(":tramai-core"))
    implementation(libs.coroutines.core)
    implementation(libs.jackson.databind)

            }
