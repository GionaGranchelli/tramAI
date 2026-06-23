import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget("21"))
    }
}

dependencies {
    api(project(":tramai-spring-boot-starter-sovereign"))
    api(project(":tramai-spring-boot-starter-sovereign-ops"))
    api(project(":tramai-persistence-jdbc"))
    api(project(":tramai-security"))
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.jdbc)

    implementation(libs.coroutines.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.junit.bom))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.coroutines.core)
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.postgresql)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
    failFast = false
    minHeapSize = "512m"
    maxHeapSize = "1g"
    jvmArgs("-XX:+UseG1GC")
}
