
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-standalone"))
    api(project(":tramai-security"))

            testImplementation(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
        testImplementation("org.junit.jupiter:junit-jupiter")
}

