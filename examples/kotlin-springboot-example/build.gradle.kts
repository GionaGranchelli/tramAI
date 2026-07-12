import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test

plugins {
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
}

springBoot {
    mainClass.set("dev.tramai.examples.springboot.ExampleApplicationKt")
}

group = "dev.tramai.examples"

val tramaiConsumerVersion = providers
    .gradleProperty("tramaiVersion")
    .orElse("0.4.0")

version = tramaiConsumerVersion.get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget("21"))
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("dev.tramai:tramai-spring:${tramaiConsumerVersion.get()}")
    implementation("dev.tramai:tramai-orchestration:${tramaiConsumerVersion.get()}")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")

    testImplementation("dev.tramai:tramai-testing:${tramaiConsumerVersion.get()}")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Test>("smokeTest") {
    group = "verification"
    description = "Runs the narrow downstream consumer smoke tests for the Spring Boot example."
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("dev.tramai.examples.springboot.ExampleSmokeTest")
    }
}

val sourceSets = the<SourceSetContainer>()

tasks.register<JavaExec>("generateNativeImageProxyConfig") {
    group = "documentation"
    description = "Generates GraalVM proxy metadata for the TramAI example @AiService interfaces."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.tramai.examples.springboot.NativeImageMetadataGenerator")
    workingDir = projectDir
    dependsOn(tasks.named("classes"))
}
