
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    api(project(":tramai-orchestration"))

    implementation(libs.coroutines.core)
    implementation(libs.hikaricp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.h2database)
    testImplementation(libs.kotlin.test.junit5)
}

