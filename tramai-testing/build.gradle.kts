
plugins {
    `java-library`
    id("tramai.test-fixtures")
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    api(project(":tramai-core"))

    implementation(libs.assertj.core)

    testFixturesApi(project(":tramai-core"))
    testFixturesApi(project(":tramai-engine"))
    testFixturesApi(project(":tramai-security"))
    testFixturesApi(project(":tramai-spring-boot-starter-sovereign-ops"))
    testFixturesApi(project(":tramai-orchestration"))
    testFixturesApi(libs.assertj.core)
    testFixturesApi(libs.coroutines.core)
    testFixturesApi(libs.coroutines.test)
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.kotlin.test.junit5)

    testImplementation(project(":tramai-standalone"))
    testImplementation(libs.coroutines.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test.junit5)
}

