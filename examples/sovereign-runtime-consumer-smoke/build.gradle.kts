import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val tramaiVersion = providers.gradleProperty("tramaiVersion").orElse("0.4.0")
val sovereignRuntimeVerificationRepo = providers
    .gradleProperty("sovereignRuntimeVerificationRepo")
    .orNull
    ?.let { file(it) }

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
    // All dev.tramai modules resolve exclusively from the sovereign runtime verification repo
    // (build/sovereign-runtime-release-verification-repo). After PR #62, this repo contains
    // the full dev.tramai dependency closure — both direct sovereign modules and transitive
    // framework modules (tramai-core, tramai-engine, tramai-standalone, tramai-structured).
    // mavenLocal is intentionally absent: enterprise evidence must prove this release candidate
    // contains the right artifacts, not that this machine had the right stuff installed.
    val repoDir = requireNotNull(sovereignRuntimeVerificationRepo) {
        "Missing -PsovereignRuntimeVerificationRepo. Consumer smoke must resolve dev.tramai from the sovereign runtime verification repo."
    }
    require(repoDir.isDirectory) {
        "Sovereign runtime verification repo does not exist: ${repoDir.absolutePath}. Run verifySovereignRuntimeSignedBundle first."
    }
    maven {
        name = "sovereignRuntimeVerificationRepo"
        url = uri(repoDir)
        content {
            includeGroup("dev.tramai")
        }
    }
    // External dependencies only — dev.tramai is explicitly blocked from remote resolution
    // to prevent stale artifacts from passing the smoke test.
    mavenCentral {
        content {
            excludeGroup("dev.tramai")
        }
    }
}

dependencies {
    // Resolve sovereign runtime modules from the local verification repo to prove
    // the signed bundle artifacts are consumer-valid. These must NOT use project()
    // dependencies — the point is to verify consumer resolution.
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
