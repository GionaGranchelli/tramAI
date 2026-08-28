
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    api(project(":tramai-core"))
    api(project(":tramai-engine"))
    api(project(":tramai-structured"))
    api(libs.kotlin.reflect)

    testImplementation(libs.coroutines.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(project(":tramai-testing"))
}

