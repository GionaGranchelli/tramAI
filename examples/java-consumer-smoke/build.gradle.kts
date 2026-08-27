plugins {
    java
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

// C3/C4: consumer compilation is fail-soft evidence, not a graph-terminating
// prerequisite. Compilation errors are recorded by the consumer probe task and
// surface as typed api-architecture diagnostics in verify060Architecture —
// the architecture report is always written before the gate fails.
tasks.withType<JavaCompile>().configureEach {
    options.isFailOnError = false
}
