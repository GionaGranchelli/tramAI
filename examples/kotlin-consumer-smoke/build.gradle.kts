plugins {
    kotlin("jvm")
}

group = "dev.tramai.examples"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// The minimal consumer classpath: only the stable core contract surface.
dependencies {
    implementation(project(":tramai-core"))
}
