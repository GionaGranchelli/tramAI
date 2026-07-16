plugins {
    kotlin("jvm") version "2.3.0"
    application
}

group = "dev.tramai.examples"
version = "0.5.0"

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
    implementation(project(":tramai-standalone"))
    implementation(project(":tramai-ollama"))

    testImplementation(kotlin("test"))
    testImplementation(project(":tramai-testing"))
}

application {
    mainClass.set("dev.tramai.examples.supportagent.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
