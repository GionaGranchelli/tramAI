import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget("25"))
    }
}

dependencies {
    api(project(":tramai-core"))
    api(project(":tramai-engine"))
    api(project(":tramai-structured"))
    api(libs.kotlin.reflect)

    testImplementation(libs.coroutines.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
}

tasks.test {
    useJUnitPlatform()
}
