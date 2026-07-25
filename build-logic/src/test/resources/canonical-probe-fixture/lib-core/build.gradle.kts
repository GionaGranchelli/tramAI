plugins {
    id("java")
    id("jacoco")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
