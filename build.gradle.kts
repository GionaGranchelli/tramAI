plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    group = "io.aurora"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
