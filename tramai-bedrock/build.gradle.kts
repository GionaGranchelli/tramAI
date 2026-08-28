
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
}



dependencies {
    api(project(":tramai-core"))

    implementation(libs.coroutines.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)

    // AWS SDK v2 for Bedrock Runtime + SigV4 auth
    implementation(libs.aws.sdk.bedrockruntime)
    implementation(libs.aws.sdk.auth)
    implementation(libs.aws.sdk.regions)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(testFixtures(project(":tramai-testing")))
    testImplementation(libs.coroutines.test)
}

