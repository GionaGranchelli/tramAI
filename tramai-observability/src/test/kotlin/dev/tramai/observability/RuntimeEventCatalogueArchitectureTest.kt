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
 * (dev.tramai.core.observation.event). No production source or class outside
 * that package may carry a `tramai.`-prefixed identifier literal (event name,
 * metric name, or attribute name) — unless the literal is (a) a catalogue-owned
 * identifier or (b) a declared Spring configuration-property namespace.
 *
 * Two layers, both fail-closed:
 *  1. Bytecode LDC scan of the four core runtime modules (core, engine,
 *     orchestration, observability) — catches `const val` inlining into
 *     consumers and is immune to source-level tricks.
 *  2. Source scan of every `tramai-*` module with `src/main` — repository-wide
 *     coverage for scheduler, server, platform, and the sovereign starters.
 *
 * The emission-path invariant (Epic 5.2 review): production emitters must
 * construct events through [RuntimeEvent.of] against catalogue definitions;
 * the legacy onWorkflowEvent/onEngineEvent(String, Map) APIs remain public for
 * backward compatibility but are no longer used by TramAI production emitters
 * with raw literals.
 */
class RuntimeEventCatalogueArchitectureTest {
    private val allowedPackagePrefix = "dev/tramai/core/observation/event/"

    /** Declared Spring configuration-property namespaces (not runtime protocol). */
    private val configPropertyNamespaces = listOf(
        "tramai.dashboard",
        "tramai.mcp",
        "tramai.default-provider",
        "tramai.providers.",
        "tramai.models.",
        "tramai.security.",
        "tramai.secrets.",
        "tramai.sovereign",
        "tramai.sovereign.persistence",
        "tramai.sovereign.ops",
        "tramai.sovereign.ops.actuator.",
        "tramai.sovereign.ops.approval-gateway",
        "tramai.sovereign.ops.approved-resume-worker",
        "tramai.sovereign.ops.outbox.worker",
        "tramai.sovereign.approved_resume_queue",
        "tramai.sovereign.approved_resume_worker",
    )

    /**
     * Subtrees of the config namespaces that are NOT configuration: the
     * sovereign ops outbox metric/tag subtree is runtime protocol and must
     * reference the catalogue, never a config allowance.
     */
    private val configNamespaceExclusions = listOf(
        "tramai.sovereign.ops.outbox.",
    )

    @Test
    fun `no tramai identifier literals outside the runtime event catalogue`() {
        val offenders = mutableListOf<String>()
        // Layer 1: bytecode LDC scan of the four core runtime modules.
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
        // Layer 2: source scan across every production module.
        for ((module, file, literal) in sourceLiterals()) {
            offenders.add("$module/$file -> $literal (source)")
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

    /**
     * Repository-wide source scan. Any `"tramai.*"` literal in a production
     * source file outside the catalogue package is an offender unless it is a
     * catalogue-owned identifier (events/metrics/attributes/namespaces) or a
     * declared configuration-property namespace.
     */
    private fun sourceLiterals(): List<Triple<String, String, String>> {
        val repoRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
        val catalogueIdentifiers = catalogueIdentifiers()
        val offenders = mutableListOf<Triple<String, String, String>>()
        val literalRegex = Regex("\"tramai\\.[a-zA-Z0-9_.-]*\"")
        repoRoot.listFiles()?.asSequence()
            ?.filter { it.isDirectory && it.name.startsWith("tramai-") }
            ?.forEach { module ->
                val mainDir = File(module, "src/main")
                if (!mainDir.isDirectory) return@forEach
                mainDir.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { file ->
                        val relative = file.relativeTo(repoRoot).path
                        if (relative.startsWith("tramai-core/src/main/kotlin/dev/tramai/core/observation/event/")) {
                            return@forEach
                        }
                        literalRegex.findAll(file.readText()).forEach { match ->
                            val literal = match.value.removeSurrounding("\"")
                            if (catalogueIdentifiers.contains(literal)) return@forEach
                            // Exact config-prefix literals (e.g. @ConfigurationProperties
                            // prefix values) are configuration, not protocol.
                            if (configPropertyNamespaces.contains(literal)) return@forEach
                            if (configNamespaceExclusions.any { literal.startsWith(it) }) {
                                offenders.add(Triple(module.name, relative, literal))
                                return@forEach
                            }
                            if (configPropertyNamespaces.any { literal.startsWith(it) }) return@forEach
                            offenders.add(Triple(module.name, relative, literal))
                        }
                    }
            }
        return offenders
    }

    private fun catalogueIdentifiers(): Set<String> {
        val eventPackage = "tramai-core/src/main/kotlin/dev/tramai/core/observation/event"
        val repoRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
        val dir = File(repoRoot, eventPackage)
        check(dir.isDirectory) { "Catalogue package unavailable: ${dir.absolutePath}" }
        val literalRegex = Regex("\"tramai\\.[a-zA-Z0-9_.-]*\"")
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { literalRegex.findAll(it.readText()) }
            .map { it.value.removeSurrounding("\"") }
            .toSet()
    }
}
