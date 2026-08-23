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

    implementation(libs.coroutines.core)

    testImplementation(testFixtures(project(":tramai-testing")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation(libs.jackson.databind)
}

tasks.test {
    useJUnitPlatform()
}
