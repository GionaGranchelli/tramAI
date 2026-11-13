plugins {
    `java-library`
    id("tramai.test-fixtures")
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}

dependencies {
    api(project(":tramai-core"))

    testFixturesApi(project(":tramai-core"))
    testFixturesApi(libs.assertj.core)
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(libs.coroutines.core)
}
