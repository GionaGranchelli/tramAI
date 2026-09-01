plugins {
    `kotlin-dsl`
    kotlin("jvm") version "2.3.0"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
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
    // CrossModuleCoverageTest vendors JUnit + JaCoCo jars from the local Gradle
    // cache into its hermetic fixture. JUnit jars arrive via kotlin("test");
    // the JaCoCo stack only if build-logic's own test classpath resolves it —
    // otherwise the jar lookup depends on some other job having resolved
    // JaCoCo in the same runner cache first (a serial-workflow ordering
    // dependency the parallel lane split breaks).
    testImplementation("org.jacoco:org.jacoco.agent:0.8.13:runtime")
    testImplementation("org.jacoco:org.jacoco.ant:0.8.13")
    testImplementation("org.jacoco:org.jacoco.core:0.8.13")
    testImplementation("org.jacoco:org.jacoco.report:0.8.13")
    testImplementation("org.ow2.asm:asm:9.8")
    testImplementation("org.ow2.asm:asm-commons:9.8")
    testImplementation("org.ow2.asm:asm-tree:9.8")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
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
