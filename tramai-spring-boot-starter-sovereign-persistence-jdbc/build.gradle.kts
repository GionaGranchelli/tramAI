
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-spring-sovereign"))
    api(project(":tramai-spring-boot-starter-sovereign-ops"))
    api(project(":tramai-persistence-jdbc"))
    api(project(":tramai-security"))
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.jdbc)

    implementation(libs.coroutines.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    annotationProcessor(libs.spring.boot.configuration.processor)

        testImplementation(platform(libs.testcontainers.bom))
            testImplementation(libs.coroutines.core)
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(testFixtures(project(":tramai-testing")))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.postgresql)
    testImplementation(libs.micrometer.core)
    testImplementation(project(":tramai-spring-boot-starter-sovereign-ops-actuator"))
}

tasks.test {
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
    failFast = false
    minHeapSize = "512m"
    maxHeapSize = "1g"
    jvmArgs("-XX:+UseG1GC")
}
