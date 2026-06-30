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
    api(libs.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

// Add Kotlin class directories to Java test compilation classpath.
// Required for Java interop tests that reference Kotlin-generated classes.
tasks.named("compileTestJava", JavaCompile::class) {
    dependsOn("compileTestKotlin")
    classpath = classpath.plus(files(
        layout.buildDirectory.dir("classes/kotlin/test"),
        layout.buildDirectory.dir("classes/kotlin/main"),
    ))
}
