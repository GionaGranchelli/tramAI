package dev.tramai.core

import dev.tramai.core.exception.ProviderException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URL
import java.net.URLClassLoader
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

class BinaryCompatibilityFixtureTest {

    @Test
    fun `fixture compiled against 0_5_0 runs against current core classes`() {
        val fixtureUrl = requireNotNull(
            javaClass.classLoader.getResource("binary-compat/fixture-v0.5.0.jar"),
        )
        val currentCoreUrl = requireNotNull(ProviderException::class.java.protectionDomain.codeSource.location)
        val output = ByteArrayOutputStream()
        val originalOut = System.out

        val text = ChildFirstCoreClassLoader(
            arrayOf(fixtureUrl, currentCoreUrl),
            javaClass.classLoader,
        ).use { loader ->
            try {
                System.setOut(PrintStream(output, true, Charsets.UTF_8))
                loader.loadClass("dev.tramai.core.binarycompat.BinaryCompatFixtureKt")
                    .getMethod("main")
                    .invoke(null)
            } finally {
                System.setOut(originalOut)
            }
            output.toString(Charsets.UTF_8)
        }

        (1..6).forEach { marker ->
            assertThat(text).contains("FIXTURE_OK_$marker")
        }
        assertThat(text).doesNotContain("FIXTURE_FAIL")
    }

    private class ChildFirstCoreClassLoader(
        urls: Array<URL>,
        parent: ClassLoader,
    ) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name)
            if (loaded == null && name.startsWith("dev.tramai.core.")) {
                loaded = runCatching { findClass(name) }.getOrNull()
            }
            if (loaded == null) loaded = super.loadClass(name, false)
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
}
