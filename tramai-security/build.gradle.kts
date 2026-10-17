
plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(project(":tramai-core"))
    implementation(project(":tramai-control-plane"))

    implementation(libs.coroutines.core)

    testImplementation(testFixtures(project(":tramai-testing")))
    testImplementation(testFixtures(project(":tramai-control-plane")))
    testImplementation(libs.coroutines.test)
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation(libs.jackson.databind)

    // 0.7.4f: a test-only seeding fixture, so :tramai-observability can prove the whole path from a persisted
    // projection through the public query port to an exported span. The projection writer and the digest
    // canonicalisation are internal to this module, and a reverse edge
    // (:tramai-security -> :tramai-observability) would be a cycle. Test fixtures are not published.
    testFixturesApi(project(":tramai-core"))
    testFixturesImplementation(project(":tramai-control-plane"))
    testFixturesImplementation(testFixtures(project(":tramai-control-plane")))
    testFixturesImplementation(libs.coroutines.core)

    // 0.7.4d: the durable read model is qualified against a real PostgreSQL instance. The store and
    // its migration live in this module because the projection types it persists are internal to it,
    // and :tramai-persistence-jdbc already depends on this module (a reverse edge would be a cycle).
    // Test scope only: nothing here changes the published artifact or its dependency surface.
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.postgresql)
}
