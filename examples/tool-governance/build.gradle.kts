plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("dev.tramai.examples.toolgovernance.ToolGovernanceMain")
}

group = "dev.tramai.examples"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val kotlinxCoroutinesVersion = "1.10.2"
val junitJupiterVersion = "5.11.4"

dependencies {
    implementation(platform(project(":tramai-bom")))
    implementation(project(":tramai-engine"))
    implementation(project(":tramai-structured"))
    implementation(project(":tramai-security"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
