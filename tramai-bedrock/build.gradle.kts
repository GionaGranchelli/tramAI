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
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)

    // AWS SDK v2 for Bedrock Runtime + SigV4 auth
    implementation(libs.aws.sdk.bedrockruntime)
    implementation(libs.aws.sdk.auth)
    implementation(libs.aws.sdk.regions)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(testFixtures(project(":tramai-testing")))
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
