plugins {
    id("java")
    id("jacoco")
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")

    repositories {
        mavenCentral()
    }

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
