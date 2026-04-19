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

    implementation(libs.assertj.core)

    testImplementation(project(":tramai-standalone"))
    testImplementation(libs.coroutines.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test.junit5)
}

tasks.test {
    useJUnitPlatform()
}
