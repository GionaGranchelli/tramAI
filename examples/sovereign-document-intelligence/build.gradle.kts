plugins {
    kotlin("jvm") version "2.3.0"
    application
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
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("dev.tramai:tramai-sovereign:0.3.1")
    implementation("dev.tramai:tramai-security:0.3.1")
    implementation("dev.tramai:tramai-core:0.3.1")
    implementation("dev.tramai:tramai-engine:0.3.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxParallelForks = 1
}
