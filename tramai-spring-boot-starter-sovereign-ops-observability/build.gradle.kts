
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-spring-boot-starter-sovereign-ops"))

    implementation(libs.opentelemetry.api)
    implementation(libs.spring.boot.autoconfigure)

                testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.opentelemetry.sdk.metrics)
    testImplementation("org.junit.jupiter:junit-jupiter")
}

