
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-orchestration"))

    implementation(project(":tramai-server"))
    implementation(libs.coroutines.core)
    testImplementation(libs.flyway.core)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.security.crypto)

            testImplementation(libs.h2database)
        testImplementation(libs.spring.boot.starter.test)
}

