import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget("21"))
    }
}

dependencies {
    api(project(":tramai-core"))
    implementation(project(":tramai-orchestration"))

    implementation(libs.opentelemetry.api)

    testImplementation(project(":tramai-engine"))
    testImplementation(project(":tramai-orchestration"))
    testImplementation(project(":tramai-structured"))
    testImplementation(libs.coroutines.core)
    testImplementation(libs.opentelemetry.exporter.otlp)
    testImplementation(libs.opentelemetry.sdk.metrics)
    testImplementation(libs.opentelemetry.sdk.trace)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
}

tasks.test {
    useJUnitPlatform()
    // The runtime-event-catalogue architecture test scans every module's
    // production sources repository-wide. Declare those sources as task inputs
    // so a literal added to ANY module re-runs the guard (otherwise Gradle
    // marks the task up-to-date and the fail-closed scan never fires).
    rootProject.subprojects.forEach { sub ->
        val mainDir = sub.layout.projectDirectory.dir("src/main")
        if (mainDir.asFile.isDirectory) {
            inputs.dir(mainDir)
        }
    }
}
