plugins {
    `kotlin-dsl`
    kotlin("jvm") version "2.3.0"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// Explicit, resolvable set of fixture jars for CrossModuleCoverageTest — the
// test copies from these resolved files instead of scanning the local Gradle
// cache (which is populated by whatever other job ran first on a shared
// runner and is not a contract the test may rely on).
val crossModuleFixtureJars by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation(kotlin("gradle-plugin", version = "2.3.0"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.3")
    implementation("org.yaml:snakeyaml:2.3")

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    // CrossModuleCoverageTest vendors JUnit + JaCoCo jars into its hermetic
    // fixture. The fixture needs JUnit 5.12.2 + platform 1.12.2 (NOT the
    // 5.10.1 that kotlin-test pulls) plus the JaCoCo 0.8.13/asm 9.8 stack.
    // crossModuleFixtureJars resolves those exact coordinates so the test can
    // copy from an explicit, guaranteed-present file set instead of scanning
    // the local Gradle cache (which is populated by whatever other job ran
    // first on a shared runner — fragile under cache eviction).
    testImplementation("org.jacoco:org.jacoco.agent:0.8.13:runtime")
    testImplementation("org.jacoco:org.jacoco.ant:0.8.13")
    testImplementation("org.jacoco:org.jacoco.core:0.8.13")
    testImplementation("org.jacoco:org.jacoco.report:0.8.13")
    testImplementation("org.ow2.asm:asm:9.8")
    testImplementation("org.ow2.asm:asm-commons:9.8")
    testImplementation("org.ow2.asm:asm-tree:9.8")

    add(
        "crossModuleFixtureJars",
        "org.junit.jupiter:junit-jupiter-api:5.12.2",
    )
    add(
        "crossModuleFixtureJars",
        "org.junit.jupiter:junit-jupiter-engine:5.12.2",
    )
    add(
        "crossModuleFixtureJars",
        "org.junit.platform:junit-platform-commons:1.12.2",
    )
    add(
        "crossModuleFixtureJars",
        "org.junit.platform:junit-platform-engine:1.12.2",
    )
    add(
        "crossModuleFixtureJars",
        "org.junit.platform:junit-platform-launcher:1.12.2",
    )
    add(
        "crossModuleFixtureJars",
        "org.apiguardian:apiguardian-api:1.1.2",
    )
    add(
        "crossModuleFixtureJars",
        "org.opentest4j:opentest4j:1.3.0",
    )
    add(
        "crossModuleFixtureJars",
        "org.jacoco:org.jacoco.agent:0.8.13:runtime",
    )
    add(
        "crossModuleFixtureJars",
        "org.jacoco:org.jacoco.ant:0.8.13",
    )
    add(
        "crossModuleFixtureJars",
        "org.jacoco:org.jacoco.core:0.8.13",
    )
    add(
        "crossModuleFixtureJars",
        "org.jacoco:org.jacoco.report:0.8.13",
    )
    add("crossModuleFixtureJars", "org.ow2.asm:asm:9.8")
    add("crossModuleFixtureJars", "org.ow2.asm:asm-commons:9.8")
    add("crossModuleFixtureJars", "org.ow2.asm:asm-tree:9.8")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
    // CrossModuleCoverageTest copies fixture jars from this explicit set
    // (resolved lazily at execution — never at configuration time, so the
    // configuration cache stays clean). doFirst runs before the test JVM
    // forks, so the property is visible to the forked test.
    doFirst {
        systemProperty(
            "tramai.crossModuleFixtureJars",
            crossModuleFixtureJars.resolve().joinToString(File.pathSeparator) { it.absolutePath },
        )
    }
}

val testSourceSet = sourceSets.named("test")
val canonicalProbeIntegrationTest by tasks.registering(Test::class) {
    description = "Runs @Tag(integration) canonical probe end-to-end tests"
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

tasks.withType<Test>().configureEach {
    systemProperty("tramai.repositoryRoot", rootProject.projectDir.parentFile.absolutePath)
    systemProperty("tramai.buildLogicRoot", rootProject.projectDir.absolutePath)
}

gradlePlugin {
    plugins {
        create("maintainabilityBaseline") {
            id = "tramai.maintainability-baseline"
            implementationClass = "dev.tramai.build.quality.MaintainabilityBaselinePlugin"
        }
        create("tramaiPublishing") {
            id = "tramai.publishing"
            implementationClass = "dev.tramai.build.publishing.TramaiPublishingPlugin"
        }
        create("tramaiReleaseVerification") {
            id = "tramai.release-verification"
            implementationClass = "dev.tramai.build.release.TramaiReleaseVerificationPlugin"
        }
        create("tramaiSovereignVerification") {
            id = "tramai.sovereign-verification"
            implementationClass = "dev.tramai.build.sovereign.TramaiSovereignVerificationPlugin"
        }
        create("tramaiSovereignLabVerification") {
            id = "tramai.sovereign-lab-verification"
            implementationClass = "dev.tramai.build.sovereign.TramaiSovereignLabVerificationPlugin"
        }
        create("tramaiKotlinLibrary") {
            id = "tramai.kotlin-library"
            implementationClass = "dev.tramai.build.conventions.TramaiKotlinLibraryPlugin"
        }
        create("tramaiJavaPlatform") {
            id = "tramai.java-platform"
            implementationClass = "dev.tramai.build.conventions.TramaiJavaPlatformPlugin"
        }
        create("tramaiTestFixtures") {
            id = "tramai.test-fixtures"
            implementationClass = "dev.tramai.build.conventions.TramaiTestFixturesPlugin"
        }
        create("tramaiTesting") {
            id = "tramai.testing"
            implementationClass = "dev.tramai.build.conventions.TramaiTestingPlugin"
        }
        create("tramaiDocsGuards") {
            id = "tramai.docs-guards"
            implementationClass = "dev.tramai.build.docs.TramaiDocsGuardsPlugin"
        }
        create("tramaiStaticAnalysis") {
            id = "tramai.static-analysis"
            implementationClass = "dev.tramai.build.quality.StaticAnalysisPlugin"
        }
        create("tramaiCompilerWarnings") {
            id = "tramai.compiler-warnings"
            implementationClass = "dev.tramai.build.quality.CompilerWarningsPlugin"
        }
        create("tramaiDependencyHygiene") {
            id = "tramai.dependency-hygiene"
            implementationClass = "dev.tramai.build.quality.DependencyHygienePlugin"
        }
        create("staticSafetyGuards") {
            id = "tramai.static-safety-guards"
            implementationClass = "dev.tramai.build.quality.StaticSafetyGuardsPlugin"
        }
        create("tramaiSupplyChain") {
            id = "tramai.supply-chain"
            implementationClass = "dev.tramai.build.supplychain.TramaiSupplyChainPlugin"
        }
    }
}

// The root compiler-warning gate does NOT cover build-logic (kotlin-dsl cannot be
// reproduced standalone; cross-build output capture is unreliable). The diagnostic
// name rendering below is kept harmless but unused by the gate.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xrender-internal-diagnostic-names")
}
