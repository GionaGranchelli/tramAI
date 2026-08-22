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
//
// Its test source set is the integration-test home: the characterization
// suite runs against core + the adapter modules under test.
dependencies {
    api(project(":tramai-spring-core"))

    testImplementation(project(":tramai-spring-provider-anthropic"))
    testImplementation(project(":tramai-spring-provider-ollama"))
    testImplementation(project(":tramai-spring-provider-openai"))
    testImplementation(project(":tramai-spring-secrets-aws"))
    testImplementation(project(":tramai-spring-secrets-file"))
    testImplementation(project(":tramai-spring-secrets-vault"))
    testImplementation(libs.coroutines.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(":tramai-security"))
}

tasks.test {
    useJUnitPlatform()
}
