package dev.tramai.examples.timeline

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Epic 0.7.5h — the launcher actually launches.
 *
 * The example's compilation tests passed while `./gradlew run` was broken, because the build pointed at a
 * class name that no source file generates (`...MainKt` instead of `...ExampleKt`). A test that only calls
 * a function can never catch that. This one resolves the class the build configures — handed to it by
 * `build.gradle.kts` as a system property, so there is one source of truth — loads it, invokes `main`, and
 * checks what a user would see on stdout.
 */
class TimelineReconstructionLauncherSmokeTest {
    @Test
    fun `the configured main class exists and prints the reference narrative`() {
        val mainClassName =
            System.getProperty("tramai.example.mainClass")
                ?: error("tramai.example.mainClass not set — the build must pass its application.mainClass")

        val main =
            Class
                .forName(mainClassName)
                .getMethod("main", Array<String>::class.java)

        val captured = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(captured, true))
        try {
            main.invoke(null, arrayOf<String>())
        } finally {
            System.setOut(originalOut)
        }

        val printed = captured.toString()
        assertThat(printed.lines().filter(String::isNotBlank)).isNotEmpty
        assertThat(printed)
            .contains("run ${TimelineReconstructionExample.RUN_ID}")
            .contains("status PARTIAL")
            .contains("history RECORDED")
            .contains("attempt 0 FAILED")
            .contains("attempt 1 SUCCEEDED")
    }
}
