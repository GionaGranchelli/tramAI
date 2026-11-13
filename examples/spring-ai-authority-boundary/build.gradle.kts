plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("dev.tramai.examples.springaiboundary.SpringAiBoundaryMain")
}

group = "dev.tramai.examples"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// XR1-0001: Spring AI is the *external* runtime being governed. It is deliberately an example-module
// dependency only — the checkpoint excludes a production starter, and no shipped TramAI module may
// depend on Spring AI.
val springAiVersion = "1.0.9"
val kotlinxCoroutinesVersion = "1.10.2"
val junitJupiterVersion = "5.11.4"

dependencies {
    implementation(platform(project(":tramai-bom")))
    implementation(project(":tramai-engine"))
    implementation(project(":tramai-security"))

    implementation(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
    // ToolCallback (the interception point) and the tool-calling dispatch live here.
    implementation("org.springframework.ai:spring-ai-model")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
