
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    id("tramai.kotlin-library")
    id("tramai.testing")
}



dependencies {
    api(libs.coroutines.core)

                testImplementation(libs.coroutines.test)
}


// Add Kotlin class directories to Java test compilation classpath.
// Required for Java interop tests that reference Kotlin-generated classes.
tasks.named("compileTestJava", JavaCompile::class) {
    dependsOn("compileTestKotlin")
    classpath = classpath.plus(files(
        layout.buildDirectory.dir("classes/kotlin/test"),
        layout.buildDirectory.dir("classes/kotlin/main"),
    ))
}
