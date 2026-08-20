package dev.tramai.observability

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.util.jar.JarFile

/**
 * Epic 5.2 boundary guard: runtime event identifiers, metric identifiers, and
 * attribute identifiers are owned by the runtime event catalogue
 * (dev.tramai.core.observation.event). No production class outside that
 * package may carry a `tramai.`-prefixed identifier literal (event name,
 * metric name, or attribute name).
 *
 * The scan is bytecode-level (LDC string constants) and fail-closed: a missing
 * classes location or an empty scan fails the test.
 */
class RuntimeEventCatalogueArchitectureTest {
    private val allowedPackagePrefix = "dev/tramai/core/observation/event/"

    @Test
    fun `no tramai identifier literals outside the runtime event catalogue`() {
        val offenders = mutableListOf<String>()
        for ((label, location) in moduleClassesLocations()) {
            val classes = loadClasses(location)
            assertThat(classes)
                .withFailMessage("Architecture scan found zero classes for module '$label' — fail closed")
                .isNotEmpty()
            for ((className, bytes) in classes) {
                if (className.startsWith(allowedPackagePrefix)) continue
                val literals = tramaiLiterals(bytes)
                if (literals.isNotEmpty()) {
                    offenders.add("$className -> ${literals.joinToString(", ")}")
                }
            }
        }
        assertThat(offenders)
            .withFailMessage(
                "Runtime event/metric/attribute identifiers must live in the runtime event catalogue " +
                    "(dev.tramai.core.observation.event). Found literals elsewhere: $offenders",
            )
            .isEmpty()
    }

    private fun moduleClassesLocations(): List<Pair<String, String>> = listOf(
        "core" to "dev.tramai.core.observation.event.RuntimeEventCatalogue",
        "engine" to "dev.tramai.engine.EngineEventObserver",
        "orchestration" to "dev.tramai.orchestration.TramaiWorker",
        "observability" to "dev.tramai.observability.OpenTelemetryAttributesKt",
    ).map { (label, className) ->
        label to checkNotNull(
            Class.forName(className).protectionDomain.codeSource.location.toString(),
        ) { "Unable to locate classes for $className" }
    }

    private fun loadClasses(location: String): List<Pair<String, ByteArray>> {
        if (location.endsWith(".jar")) {
            return JarFile(File(URI.create(location))).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .map { it.name.removeSuffix(".class") to jar.getInputStream(it).readBytes() }
                    .toList()
            }
        }
        val dir = File(URI.create(location))
        check(dir.isDirectory) { "Classes location unavailable: ${dir.absolutePath}" }
        return File(dir, "dev/tramai")
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { classFile ->
                classFile.relativeTo(dir).path.removeSuffix(".class") to classFile.readBytes()
            }
            .toList()
    }

    private fun tramaiLiterals(bytes: ByteArray): List<String> {
        val literals = mutableListOf<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                override fun visitLdcInsn(value: Any?) {
                    if (value is String && value.startsWith("tramai.")) {
                        literals.add(value)
                    }
                }
            }
        }, 0)
        return literals
    }
}
