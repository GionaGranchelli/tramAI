
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-core"))
    api(project(":tramai-engine"))
    api(project(":tramai-structured"))
    api(libs.kotlin.reflect)

    testImplementation(libs.coroutines.core)
                testImplementation(project(":tramai-testing"))
}

