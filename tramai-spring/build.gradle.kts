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
    }
}

dependencies {
    api(project(":tramai-standalone"))

    implementation(project(":tramai-anthropic"))
    implementation(project(":tramai-openai"))
    implementation(project(":tramai-ollama"))
    implementation(libs.spring.context)
    implementation(libs.spring.boot.autoconfigure)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.coroutines.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.spring.boot.starter.test)
}

tasks.test {
    useJUnitPlatform()
}
