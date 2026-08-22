plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Characterization oracle: a minimal Spring consumer depends ONLY on tramai-spring.
    // No provider modules, no AWS SDK. Whatever provider SDKs land on the test runtime
    // classpath below are transitive leaks from tramai-spring.
    testImplementation(project(":tramai-spring"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.assertj.core)
    testImplementation(libs.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
}
