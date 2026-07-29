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
    implementation(libs.mcp.sdk.client)

    testImplementation(project(":tramai-engine"))
    testImplementation(project(":tramai-testing"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.mcp.sdk.server)
    testImplementation(libs.h2database)
}

tasks.test {
    useJUnitPlatform()
}
