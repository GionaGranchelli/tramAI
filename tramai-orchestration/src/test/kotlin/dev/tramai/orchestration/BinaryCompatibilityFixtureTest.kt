package dev.tramai.orchestration

import java.net.URL
import java.net.URLClassLoader
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * Binary compatibility guard for the v0.5.0 external-step ABI.
 *
 * The fixture jar ([binary-compat/fixture-v0.5.0.jar]) is compiled against tag
 * v0.5.0 from [BinaryCompatFixture.kt] (see the README next to it for the rebuild
 * command). It exercises the OLD public constructors of all five workflow
 * exception families and the OLD [WorkflowBuilder] build flow. Running it under a
 * child-first classloader over the CURRENT orchestration classes proves that old
 * Kotlin constructor calls and workflow-building code still link and execute.
 */
class BinaryCompatibilityFixtureTest {

    @Test
    fun `fixture compiled against 0_5_0 runs against current orchestration classes`() {
        val fixtureUrl = requireNotNull(
            javaClass.classLoader.getResource("binary-compat/fixture-v0.5.0.jar"),
        )
        val currentOrchestrationUrl = requireNotNull(
            WorkflowHttpException::class.java.protectionDomain.codeSource.location,
        )

        val markers = ChildFirstOrchestrationClassLoader(
            arrayOf(fixtureUrl, currentOrchestrationUrl),
            javaClass.classLoader,
        ).use { loader ->
            loader.loadClass("dev.tramai.orchestration.BinaryCompatFixtureKt")
                .getMethod("binaryCompatFixtureMarkers")
                .invoke(null) as String
        }

        (1..17).forEach { marker ->
            assertThat(markers).contains("FIXTURE_OK_$marker")
        }
        assertThat(markers).doesNotContain("FIXTURE_FAIL")
    }

    private class ChildFirstOrchestrationClassLoader(
        urls: Array<URL>,
        parent: ClassLoader,
    ) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name)
            if (loaded == null && name.startsWith("dev.tramai.orchestration.")) {
                loaded = runCatching { findClass(name) }.getOrNull()
            }
            if (loaded == null) loaded = super.loadClass(name, false)
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
}
