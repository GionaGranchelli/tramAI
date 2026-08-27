
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    api(project(":tramai-spring-boot-starter-sovereign-ops"))

    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.actuator)
    implementation(libs.micrometer.core)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.actuator.autoconfigure)
    testImplementation(libs.micrometer.core)
    testImplementation("org.junit.jupiter:junit-jupiter")
}

