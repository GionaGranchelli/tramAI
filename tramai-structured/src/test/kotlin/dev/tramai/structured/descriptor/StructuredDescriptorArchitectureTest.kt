package dev.tramai.structured.descriptor

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 7.1 architecture guard: source-language introspection (Kotlin
 * reflection / Jackson JavaBean introspection) must live only inside
 * descriptor compilation, and descriptor consumers must be pure
 * descriptor → schema / node / value translations.
 *
 * Mutation-resistant: asserts compiled bytecode references, not source text,
 * so a helper method that smuggles reflection back into the handler or a
 * consumer still fails the guard.
 */
class StructuredDescriptorArchitectureTest {

    private val compilerPackage = "dev/tramai/structured/descriptor/"
    private val handlerClass = "dev/tramai/structured/JacksonStructuredOutputHandler"

    // Kotlin reflection entry points that must not appear outside the Kotlin compiler.
    private val kotlinReflectionRefs = setOf(
        "kotlin/reflect/full/KClasses",
        "kotlin/reflect/full/KClassMembers",
        "kotlin/reflect/KProperty1",
    )

    // Jackson introspection entry points that must not appear outside the JavaBean compiler.
    private val jacksonIntrospectionRefs = setOf(
        "com/fasterxml/jackson/databind/introspect/BeanPropertyDefinition",
        "com/fasterxml/jackson/databind/BeanDescription",
    )

    // Type tokens that must not appear in descriptor consumers (renderer, validators).
    private val sourceTypeRefs = setOf(
        "kotlin/reflect/KType",
        "kotlin/reflect/KClass",
        "com/fasterxml/jackson/databind/JavaType",
    )

    @Test
    fun `handler performs no source-language introspection`() {
        val refs = classMethodRefsOf(handlerClass)

        assertThat(refs.owners)
            .withFailMessage("handler must not use Kotlin reflection: ${refs.owners}")
            .doesNotContainAnyElementsOf(kotlinReflectionRefs)
        assertThat(refs.owners)
            .withFailMessage("handler must not use Jackson introspection: ${refs.owners}")
            .doesNotContainAnyElementsOf(jacksonIntrospectionRefs)
    }

    @Test
    fun `schema renderer consumes only descriptors`() {
        val refs = classMethodRefsOf(compilerPackage + "StructuredSchemaRenderer")

        assertThat(refs.owners)
            .withFailMessage("renderer must not depend on source types: ${refs.owners}")
            .doesNotContainAnyElementsOf(sourceTypeRefs)
    }

    @Test
    fun `shape validator consumes only descriptors`() {
        val refs = classMethodRefsOf(compilerPackage + "StructuredJsonShapeValidator")

        assertThat(refs.owners)
            .withFailMessage("shape validator must not depend on source types: ${refs.owners}")
            .doesNotContainAnyElementsOf(sourceTypeRefs)
    }

    @Test
    fun `value validator consumes only descriptors`() {
        val refs = classMethodRefsOf(compilerPackage + "StructuredValueValidator")

        assertThat(refs.owners)
            .withFailMessage("value validator must not depend on source types: ${refs.owners}")
            .doesNotContainAnyElementsOf(sourceTypeRefs)
    }

    @Test
    fun `only the Kotlin compiler uses Kotlin reflection`() {
        val offenders = moduleClasses()
            .filter { it.startsWith("dev/tramai/structured/") }
            .filterNot { it.startsWith("dev/tramai/structured/descriptor/KotlinStructuredTypeCompiler") }
            .filterNot { it.endsWith("PositiveControlFixture") }
            .filter { clazz ->
                val refs = classMethodRefsOf(clazz)
                refs.owners.any { it in kotlinReflectionRefs }
            }

        assertThat(offenders)
            .withFailMessage("Kotlin reflection escaped descriptor compilation: $offenders")
            .isEmpty()
    }

    @Test
    fun `only the JavaBean compiler uses Jackson introspection`() {
        val offenders = moduleClasses()
            .filter { it.startsWith("dev/tramai/structured/") }
            .filterNot { it.startsWith("dev/tramai/structured/descriptor/JacksonJavaBeanStructuredTypeCompiler") }
            .filterNot { it.endsWith("PositiveControlFixture") }
            .filter { clazz ->
                val refs = classMethodRefsOf(clazz)
                refs.owners.any { it in jacksonIntrospectionRefs }
            }

        assertThat(offenders)
            .withFailMessage("Jackson introspection escaped descriptor compilation: $offenders")
            .isEmpty()
    }

    @Test
    fun `positive control - the ASM scan flags a planted reflection leak`() {
        // Proves the bytecode visitor + ref-set actually detect a leak; without
        // this, a broken scan (wrong classpath layout, renamed owners) would be
        // a silent no-op that always passes.
        val refs = classMethodRefsOf(compilerPackage + "PositiveControlFixture")

        assertThat(refs.owners)
            .withFailMessage("positive control fixture must reference Kotlin reflection")
            .containsAnyElementsOf(kotlinReflectionRefs)
        assertThat(refs.owners)
            .withFailMessage("positive control fixture must reference Jackson introspection")
            .containsAnyElementsOf(jacksonIntrospectionRefs)
    }

    // ------------------------------------------------------------------
    // Bytecode helpers (verbatim convention from orchestration guards)
    // ------------------------------------------------------------------

    private data class MethodRefs(
        val owners: Set<String>,
        val names: Set<String>,
    )

    private fun classMethodRefsOf(internalName: String): MethodRefs {
        val resource = internalName + ".class"
        val stream = javaClass.classLoader.getResourceAsStream(resource)
            ?: error("Class not loadable: $internalName (fail-closed)")
        stream.use { input ->
            val reader = ClassReader(input)
            val owners = mutableSetOf<String>()
            val names = mutableSetOf<String>()
            reader.accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor {
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitMethodInsn(
                                opcode: Int,
                                owner: String,
                                name: String,
                                descriptor: String,
                                isInterface: Boolean,
                            ) {
                                owners.add(owner)
                                names.add(name)
                            }

                            override fun visitFieldInsn(
                                opcode: Int,
                                owner: String,
                                name: String,
                                descriptor: String,
                            ) {
                                owners.add(owner)
                                names.add(name)
                            }

                            override fun visitTypeInsn(opcode: Int, type: String) {
                                owners.add(type)
                            }
                        }
                    }
                },
                ClassReader.SKIP_DEBUG,
            )
            return MethodRefs(owners, names)
        }
    }

    private fun moduleClasses(): List<String> {
        val dir = javaClass.classLoader.getResource("dev/tramai/structured/")
            ?: error("structured package not on classpath (fail-closed)")
        val path = java.nio.file.Paths.get(dir.toURI())
        return java.nio.file.Files.walk(path)
            .filter { it.toString().endsWith(".class") }
            .map { it.toString() }
            .map { it.substring(it.indexOf("dev/tramai/structured")) }
            .map { it.removeSuffix(".class") }
            .toList()
    }
}
