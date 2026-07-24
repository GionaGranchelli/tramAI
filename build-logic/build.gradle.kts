plugins {
    `kotlin-dsl`
    kotlin("jvm") version "2.3.0"
    jacoco
    id("info.solidsoft.pitest") version "1.19.0"
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
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

pitest {
    targetClasses.set(setOf("dev.tramai.build.quality.*"))
    targetTests.set(setOf("dev.tramai.build.quality.*Test"))
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
}

gradlePlugin {
    plugins {
        create("maintainabilityBaseline") {
            id = "tramai.maintainability-baseline"
            implementationClass = "dev.tramai.build.quality.MaintainabilityBaselinePlugin"
        }
    }
}
