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

gradlePlugin {
    plugins {
        create("maintainabilityBaseline") {
            id = "tramai.maintainability-baseline"
            implementationClass = "dev.tramai.build.quality.MaintainabilityBaselinePlugin"
        }
    }
}
