
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-spring-boot-starter-sovereign-ops"))

    compileOnly(libs.spring.boot.starter.security)
    compileOnly(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.coroutines.core)

    annotationProcessor(libs.spring.boot.configuration.processor)

                testImplementation(libs.coroutines.core)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.security)
    testImplementation(libs.spring.boot.starter.oauth2.resource.server)
    testImplementation(libs.spring.security.test)
    testImplementation("org.junit.jupiter:junit-jupiter")
}

