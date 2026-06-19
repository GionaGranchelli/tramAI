import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val tramaiVersion = providers.gradleProperty("tramaiVersion").orElse("0.3.1")

plugins {
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
}

springBoot {
    mainClass.set("dev.tramai.examples.sovereign.consumersmoke.SmokeApplicationKt")
}

group = "dev.tramai.examples"
version = tramaiVersion.get()

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
    // dev.tramai modules must resolve from mavenLocal — the point is verifying
    // that locally published artifacts are consumer-valid. Block remote resolution
    // so stale Maven Central or other remote artifacts cannot make the test pass
    // accidentally.
    exclusiveContent {
        forRepository { mavenLocal() }
        filter { includeGroup("dev.tramai") }
    }
    mavenCentral()
}

dependencies {
    // Resolve sovereign runtime modules from mavenLocal to prove they are publishable.
    // These must NOT use project() dependencies — the point is to verify consumer resolution.
    implementation("dev.tramai:tramai-spring-boot-starter-sovereign:${tramaiVersion.get()}")
    implementation("dev.tramai:tramai-spring-boot-starter-sovereign-persistence-file:${tramaiVersion.get()}")
    implementation("dev.tramai:tramai-spring-boot-starter-sovereign-ops:${tramaiVersion.get()}")
    implementation("dev.tramai:tramai-spring-boot-starter-sovereign-ops-observability:${tramaiVersion.get()}")

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}
