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

    implementation(libs.coroutines.core)
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.11.1")

    testImplementation(project(":tramai-engine"))
    testImplementation(project(":tramai-testing"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation("io.modelcontextprotocol:kotlin-sdk-server:0.11.1")
}

tasks.test {
    useJUnitPlatform()
}
