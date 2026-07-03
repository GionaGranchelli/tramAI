import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
}

springBoot {
    mainClass.set("dev.tramai.examples.spring.SpringSovereignStarterApplicationKt")
}

group = "dev.tramai.examples"
version = "0.3.1"

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

val kotlinxCoroutinesVersion = "1.10.2"

dependencies {
    // Sovereign runtime
    implementation(project(":tramai-spring-boot-starter-sovereign"))
    implementation(project(":tramai-spring"))

    // JDBC sovereign persistence
    implementation(project(":tramai-spring-boot-starter-sovereign-persistence-jdbc"))
    implementation(project(":tramai-spring-boot-starter-sovereign-ops"))

    // REST control plane (for sovereign-lab profile)
    implementation(project(":tramai-spring-boot-starter-sovereign-ops-rest"))

    // OpenAI provider (for sovereign-lab local model endpoint)
    implementation(project(":tramai-openai"))

    // Auto-configure OpenAI-compatible providers from tramai.providers YAML
    implementation(project(":tramai-spring-boot-starter-local-provider-openai"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    // PostgreSQL driver (runtime only)
    runtimeOnly("org.postgresql:postgresql")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
    testImplementation("io.zonky.test:embedded-postgres:2.0.7")
    testImplementation(testFixtures(project(":tramai-engine")))
}

tasks.test {
    useJUnitPlatform {
        excludeTags("e2e")
    }
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

val e2eTest by tasks.registering(Test::class) {
    description = "Runs end-to-end tests (uses embedded PostgreSQL, no Docker required)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    useJUnitPlatform {
        includeTags("e2e")
    }

    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }

    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(e2eTest)
}
