
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    api(project(":tramai-core"))
    api(project(":tramai-security"))
    api(project(":tramai-sovereign"))
    api(project(":tramai-spring-sovereign"))

    implementation(project(":tramai-engine"))
    implementation(libs.coroutines.core)
    implementation(libs.spring.boot.autoconfigure)
    // spring-boot-starter-web is NOT added here.
    // REST control plane lives in tramai-spring-boot-starter-sovereign-ops-rest

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.coroutines.core)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.jackson.databind)
    testImplementation(testFixtures(project(":tramai-testing")))
}

