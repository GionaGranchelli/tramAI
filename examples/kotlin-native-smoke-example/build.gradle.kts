import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.nio.file.Path

plugins {
    application
    kotlin("jvm") version "2.3.0"
}

group = "dev.tramai.examples"
version = "0.3.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget("21"))
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(kotlin("reflect"))
    implementation("dev.tramai:tramai-standalone:0.3.0")
}

application {
    mainClass.set("dev.tramai.examples.nativesmoke.NativeSmokeApplicationKt")
}

val sourceSets = the<SourceSetContainer>()
val nativeImageBinaryName = "tramai-native-smoke"
val nativeImageOutputDirectory = layout.buildDirectory.dir("native/nativeSmoke")

fun nativeImageExecutable(): String {
    val executableName = if (System.getProperty("os.name").startsWith("Windows")) {
        "native-image.cmd"
    } else {
        "native-image"
    }

    val candidates = listOfNotNull(
        providers.environmentVariable("GRAALVM_HOME").orNull?.let { Path.of(it).resolve("bin").resolve(executableName) },
        providers.environmentVariable("JAVA_HOME").orNull?.let { Path.of(it).resolve("bin").resolve(executableName) },
        Path.of(System.getProperty("java.home")).resolve("bin").resolve(executableName),
    )

    return candidates.firstOrNull { Files.isRegularFile(it) }?.toString()
        ?: error("Could not locate native-image. Set GRAALVM_HOME or JAVA_HOME to a GraalVM installation that provides native-image.")
}

fun nativeImageBinaryPath(): String {
    val extension = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
    return nativeImageOutputDirectory.get().file("$nativeImageBinaryName$extension").asFile.absolutePath
}

tasks.register<JavaExec>("generateNativeImageProxyConfig") {
    group = "documentation"
    description = "Generates GraalVM proxy metadata for the native smoke example."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.tramai.examples.nativesmoke.NativeImageMetadataGeneratorKt")
    workingDir = projectDir
    dependsOn(tasks.named("classes"))
}

tasks.register<Exec>("nativeSmokeCompile") {
    group = "verification"
    description = "Compiles the native smoke example with GraalVM native-image."
    dependsOn(tasks.named("generateNativeImageProxyConfig"))

    doFirst {
        commandLine(
            nativeImageExecutable(),
            "--no-fallback",
            "-H:Name=$nativeImageBinaryName",
            "-H:Path=${nativeImageOutputDirectory.get().asFile.absolutePath}",
            "-cp",
            sourceSets["main"].runtimeClasspath.asPath,
            application.mainClass.get(),
        )
    }
}

tasks.register<Exec>("nativeSmokeRun") {
    group = "verification"
    description = "Runs the compiled GraalVM native smoke binary."
    dependsOn(tasks.named("nativeSmokeCompile"))

    doFirst {
        commandLine(nativeImageBinaryPath())
    }
}

tasks.test {
    enabled = false
}
