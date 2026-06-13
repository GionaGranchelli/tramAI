plugins {
    kotlin("jvm")
    application
}

group = "dev.tramai.examples"

dependencies {
    implementation(project(":tramai-sovereign"))
    implementation(project(":tramai-security"))
    implementation(project(":tramai-core"))
    implementation(project(":tramai-engine"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.tramai.examples.offline.OfflineVerificationMainKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxParallelForks = 1
}
