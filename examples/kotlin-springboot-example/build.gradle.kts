import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.SourceSetContainer

plugins {
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
}

group = "dev.tramai.examples"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget("25"))
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("dev.tramai:tramai-spring:0.1.0-SNAPSHOT")
    implementation("dev.tramai:tramai-orchestration:0.1.0-SNAPSHOT")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")

    testImplementation("dev.tramai:tramai-testing:0.1.0-SNAPSHOT")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.test {
    useJUnitPlatform()
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
