package dev.tramai.core.observation.event

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Proves docs/reference/runtime-event-catalogue.md is generated from the
 * runtime definitions and has not drifted (deterministic, CI-checkable).
 */
class RuntimeEventCatalogueDocumentationTest {
    @Test
    fun `reference documentation matches the renderer output`() {
        val rendered = RuntimeEventCatalogueRenderer.render()
        val repoRoot = File(System.getProperty("user.dir")).let { cwd ->
            generateSequence(cwd) { it.parentFile }.first { it.resolve("settings.gradle.kts").isFile }
        }
        val doc = File(repoRoot, "docs/reference/runtime-event-catalogue.md")
        assertThat(doc).exists()
        assertThat(doc.readText())
            .withFailMessage(
                "docs/reference/runtime-event-catalogue.md drifted from the runtime catalogue. " +
                    "Regenerate it from RuntimeEventCatalogueRenderer.",
            )
            .isEqualTo(rendered)
    }
}
