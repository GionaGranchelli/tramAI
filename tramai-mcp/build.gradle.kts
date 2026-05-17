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
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

dependencies {
    api(project(":tramai-server"))

    implementation(project(":tramai-structured"))
    implementation(libs.coroutines.core)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.context)
    implementation(libs.okio)
    implementation(libs.ktor.server.cio)
    implementation(libs.mcp.sdk.server)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.assertj.core)
    testImplementation(libs.h2database)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.mcp.sdk.client)
}

tasks.test {
    useJUnitPlatform()
}
