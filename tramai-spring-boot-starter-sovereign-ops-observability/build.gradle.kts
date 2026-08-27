
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    api(project(":tramai-spring-boot-starter-sovereign-ops"))

    implementation(libs.opentelemetry.api)
    implementation(libs.spring.boot.autoconfigure)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.opentelemetry.sdk.metrics)
    testImplementation("org.junit.jupiter:junit-jupiter")
}

