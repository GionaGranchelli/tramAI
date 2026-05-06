plugins {
    kotlin("jvm") version "2.3.0"
    application
}

group = "dev.tramai.examples"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":tramai-standalone"))
    implementation(project(":tramai-ollama"))
}

application {
    mainClass.set("dev.tramai.examples.supportagent.MainKt")
}
