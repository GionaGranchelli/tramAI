
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    // Canonical starter: dependency composition only. The standard runtime
    // integration lives in tramai-spring-core, the sovereign runtime
    // integration in tramai-spring-sovereign; this artifact makes both
    // available to a Spring Boot application and nothing more.
    api(project(":tramai-spring-core"))
    api(project(":tramai-spring-sovereign"))

    implementation(libs.spring.boot.autoconfigure)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.coroutines.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(":tramai-spring-provider-openai"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

