
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-orchestration"))

    implementation(project(":tramai-scheduler"))
    implementation(libs.coroutines.core)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.web)

            testImplementation(libs.h2database)
        testImplementation(libs.spring.boot.starter.test)
}

