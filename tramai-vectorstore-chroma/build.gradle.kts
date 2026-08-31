
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-vectorstore-spi"))

    implementation(project(":tramai-core"))
    implementation(libs.coroutines.core)
    implementation(libs.jackson.databind)

            }
