
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-spring-sovereign"))
    api(project(":tramai-persistence-file"))
    api(project(":tramai-spring-boot-starter-sovereign-ops"))

    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.spring.boot.autoconfigure)

    annotationProcessor(libs.spring.boot.configuration.processor)

            testImplementation(libs.coroutines.core)
        testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(testFixtures(project(":tramai-testing")))
}

