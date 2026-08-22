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

// Anthropic provider adapter for Spring (Epic 6.3).
// Owns Anthropic construction only; generic assembly lives in tramai-spring-core.
dependencies {
    api(project(":tramai-spring-core"))
    implementation(project(":tramai-anthropic"))

    implementation(libs.spring.context)
    implementation(libs.spring.boot.autoconfigure)

    testImplementation(libs.coroutines.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.spring.boot.starter.test)
}

tasks.test {
    useJUnitPlatform()
}
