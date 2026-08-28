
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    api(project(":tramai-core"))
    api(project(":tramai-security"))
    api(project(":tramai-sovereign"))
    api(project(":tramai-spring-core"))

    implementation(libs.spring.boot.autoconfigure)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(project(":tramai-spring"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.coroutines.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.junit.jupiter:junit-jupiter")
}

