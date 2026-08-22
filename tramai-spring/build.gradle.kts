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

// Epic 6.3 compatibility facade: generic Spring integration moved to
// tramai-spring-core. This artifact deliberately no longer pulls provider
// adapters or secret-backend SDKs; consumers select them explicitly.
dependencies {
    api(project(":tramai-spring-core"))
}

tasks.test {
    useJUnitPlatform()
}
