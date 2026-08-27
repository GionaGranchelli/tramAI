
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
}

