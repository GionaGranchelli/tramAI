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

application {
    mainClass.set("dev.tramai.examples.timeline.TimelineReconstructionMainKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
