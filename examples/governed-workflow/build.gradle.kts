plugins {
    kotlin("jvm")
    application
}

group = "dev.tramai.examples"

val junitJupiterVersion = "5.11.4"
val assertjVersion = "3.27.3"

dependencies {
    implementation(project(":tramai-orchestration"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
}

application {
    mainClass.set("dev.tramai.examples.governed.GovernedWorkflowMainKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
