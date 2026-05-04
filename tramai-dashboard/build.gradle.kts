import com.github.gradle.node.npm.task.NpxTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("com.github.node-gradle.node") version "7.1.0"
}

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

node {
    version.set("22.12.0")
    npmVersion.set("10.9.2")
    download.set(true)
    nodeProjectDir.set(file("src/main/frontend"))
}

val buildDashboard by tasks.registering(NpxTask::class) {
    val tramaiDev = providers.gradleProperty("tramai.dev")
        .map(String::toBooleanStrictOrNull)
        .orElse(false)

    command.set("vite")
    args.set(listOf("build", "--emptyOutDir"))
    dependsOn("npmInstall")
    inputs.dir("src/main/frontend/src")
    outputs.dir("src/main/frontend/dist")
    environment.set(mapOf("TRAMAI_DEV" to tramaiDev.get().toString()))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildDashboard)
    from("src/main/frontend/dist") {
        into("META-INF/tramai-dashboard")
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    compileOnly(libs.spring.boot.autoconfigure)
}
