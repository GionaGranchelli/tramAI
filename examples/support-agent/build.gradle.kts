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
    implementation("dev.tramai:tramai-standalone:0.3.1")
    implementation("dev.tramai:tramai-ollama:0.3.1")

    testImplementation(kotlin("test"))
    testImplementation("dev.tramai:tramai-testing:0.3.1")
}

application {
    mainClass.set("dev.tramai.examples.supportagent.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
