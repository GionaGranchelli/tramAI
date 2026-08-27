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
    implementation("org.yaml:snakeyaml:2.3")

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
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
    }
}
