plugins {
    kotlin("jvm")
    application
}

group = "dev.tramai.examples"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// The example uses only shipped public API: the timeline query, the reconstructor and the stores.
dependencies {
    implementation(project(":tramai-core"))
    implementation(project(":tramai-security"))
    implementation(project(":tramai-persistence-jdbc"))
    implementation(libs.coroutines.core)

    testImplementation(testFixtures(project(":tramai-security")))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.assertj.core)
    testImplementation(libs.coroutines.core)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.postgresql)
}

// The top-level `fun main()` lives in TimelineReconstructionExample.kt, so the generated class is
// TimelineReconstructionExampleKt — not a name derived from the file's main function.
val exampleMainClass = "dev.tramai.examples.timeline.TimelineReconstructionExampleKt"

application {
    mainClass.set(exampleMainClass)
}

tasks.withType<Test> {
    useJUnitPlatform()
    // The launcher smoke test loads exactly the class this build configures, so a wrong mainClass
    // fails a test instead of only failing `./gradlew run`.
    systemProperty("tramai.example.mainClass", exampleMainClass)
}
