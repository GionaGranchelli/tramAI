plugins {
    kotlin("jvm") version "2.3.0"
    application
}

group = "dev.tramai.examples"
version = "0.4.0"

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

val tramaiVersion = "0.4.0"

dependencies {
    implementation("dev.tramai:tramai-standalone:$tramaiVersion")
    implementation("dev.tramai:tramai-ollama:$tramaiVersion")

    testImplementation(kotlin("test"))
    testImplementation("dev.tramai:tramai-testing:$tramaiVersion")
}

application {
    mainClass.set("dev.tramai.examples.supportagent.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
