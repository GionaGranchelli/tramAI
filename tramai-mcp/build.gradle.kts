import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget("25"))
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
    implementation("com.squareup.okio:okio:3.10.2")
    implementation("io.ktor:ktor-server-cio-jvm:3.3.3")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.11.1")

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.assertj.core)
    testImplementation(libs.h2database)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.web)
    testImplementation("io.modelcontextprotocol:kotlin-sdk-client:0.11.1")
}

tasks.test {
    useJUnitPlatform()
}
