plugins {
    kotlin("jvm")
    application
}

group = "dev.tramai.examples"

val junitJupiterVersion = "5.11.4"
val assertjVersion = "3.27.3"

dependencies {
    implementation(project(":tramai-sovereign"))
    implementation(project(":tramai-security"))
    implementation(project(":tramai-core"))
    implementation(project(":tramai-engine"))

    testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxParallelForks = 1
}

application {
    mainClass.set("dev.tramai.examples.sovereign.DocumentIntelligenceMainKt")
}
