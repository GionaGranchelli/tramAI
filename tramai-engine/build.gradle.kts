import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    `java-test-fixtures`
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
    implementation(project(":tramai-security"))
    testFixturesImplementation(project(":tramai-security"))

    implementation(libs.coroutines.core)
    implementation(libs.kotlin.reflect)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(project(":tramai-structured"))
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
