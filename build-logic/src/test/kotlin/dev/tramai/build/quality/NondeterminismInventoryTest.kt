package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scanner discriminators for Epic 8.3d PR 2 (S0-A..S0-H).
 *
 * Each test builds a minimal MeasurementContext over a temp dir and asserts
 * the canonical scanner's emission for the audited direct nondeterminism forms.
 */
class NondeterminismInventoryTest {

    @TempDir
    lateinit var tempDir: File

    private fun context(moduleName: String = "sample", src: String): MeasurementContext {
        val moduleDir = File(tempDir, "sample")
        val srcDir = File(moduleDir, "src/main/kotlin")
        srcDir.mkdirs()
        File(srcDir, "Sample.kt").writeText(src)
        val module = DiscoveredModule(
            name = moduleName,
            path = ":sample",
            projectDir = moduleDir,
            buildFile = File(moduleDir, "build.gradle.kts"),
            sourceDirs = listOf(srcDir),
            testSourceDirs = listOf(File(moduleDir, "src/test/kotlin")),
            testFixtureDirs = listOf(File(moduleDir, "src/testFixtures/kotlin")),
            publishable = false,
            layer = "core",
            apiStability = "internal",
        )
        return MeasurementContext(tempDir, listOf(module))
    }

    private fun sourcesOf(src: String): List<String> =
        NondeterminismInventory(context(src = src)).inventory().map { it.source }

    @Test
    fun `S0-A System nanoTime call detected`() {
        assertTrue("System.nanoTime()" in sourcesOf("fun f() { System.nanoTime() }"))
    }

    @Test
    fun `S0-B System callable reference nanoTime detected`() {
        assertTrue("System::nanoTime" in sourcesOf("val t = System::nanoTime"))
    }

    @Test
    fun `S0-C currentTimeMillis call and callable reference both detected`() {
        val sources = sourcesOf(
            """
            fun f() {
                val a = System.currentTimeMillis()
                val b = System::currentTimeMillis
            }
            """.trimIndent()
        )
        assertTrue("System.currentTimeMillis()" in sources)
        assertTrue("System::currentTimeMillis" in sources)
    }

    @Test
    fun `S0-D UUID randomUUID detected`() {
        assertTrue("UUID.randomUUID()" in sourcesOf("val id = UUID.randomUUID()"))
    }

    @Test
    fun `S0-E kotlin random singleton nextDouble detected with canonical label`() {
        val sources = sourcesOf("val r = kotlin.random.Random.nextDouble()")
        assertEquals(listOf("kotlin.random.Random.nextDouble()"), sources)
    }

    @Test
    fun `S0-E2 bare Random singleton nextInt detected once`() {
        // Bare singleton (imported kotlin.random.Random) — instance receivers and
        // the fully-qualified form must not double-count.
        val sources = sourcesOf(
            """
            fun f() {
                val a = Random.nextInt(10)
                val b = kotlin.random.Random.nextLong()
                val r = java.util.Random()
                r.nextBytes(ByteArray(4))
            }
            """.trimIndent()
        )
        assertEquals(
            listOf("Random.nextInt()", "kotlin.random.Random.nextLong()"),
            sources
        )
    }

    @Test
    fun `S0-E3 Random nextBytes and unsigned variants detected`() {
        val sources = sourcesOf(
            """
            fun f() {
                val a = kotlin.random.Random.nextUBytes(4)
                val b = Random.nextBytes(ByteArray(4))
            }
            """.trimIndent()
        )
        assertEquals(
            listOf("kotlin.random.Random.nextUBytes()", "Random.nextBytes()"),
            sources
        )
    }

    @Test
    fun `S0-F ThreadLocalRandom Math random SecureRandom behavior preserved`() {
        val sources = sourcesOf(
            """
            import java.util.concurrent.ThreadLocalRandom
            fun f() {
                val a = ThreadLocalRandom.current().nextDouble()
                val b = Math.random()
                val c = SecureRandom()
            }
            """.trimIndent()
        )
        assertTrue("ThreadLocalRandom" in sources)
        assertTrue("Math.random()" in sources)
        assertTrue("SecureRandom()" in sources)
    }

    @Test
    fun `S0-F2 SecureRandom instance method calls are not constructor findings`() {
        // secureRandom.nextBytes(...) on an instance is NOT a direct SecureRandom()
        // construction — the scanner must not report it.
        val sources = sourcesOf(
            """
            fun f(r: java.security.SecureRandom) {
                r.nextBytes(ByteArray(4))
            }
            """.trimIndent()
        )
        assertTrue(sources.isEmpty())
    }

    @Test
    fun `S0-G scanner result ordering is deterministic`() {
        val src = """
            fun f() {
                val a = System.currentTimeMillis()
                val b = UUID.randomUUID()
                val c = Clock.systemUTC()
                val d = System.nanoTime()
                val e = kotlin.random.Random.nextDouble()
            }
        """.trimIndent()
        val first = NondeterminismInventory(context(src = src)).inventory()
        val second = NondeterminismInventory(context(src = src)).inventory()
        assertEquals(
            first.map { "${it.file}:${it.line}:${it.source}" },
            second.map { "${it.file}:${it.line}:${it.source}" }
        )
        // Order is file, then line, then source — provably sorted.
        val keys = first.map { Triple(it.file, it.line, it.source) }
        val sortedKeys = keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
        assertEquals(sortedKeys, keys)
    }

    @Test
    fun `S0-H test fixtures and non-main sources are excluded`() {
        val moduleDir = File(tempDir, "sample")
        File(moduleDir, "src/test/kotlin").mkdirs()
        File(moduleDir, "src/test/kotlin/TestFile.kt").writeText(
            "fun t() { UUID.randomUUID(); System.nanoTime() }"
        )
        File(moduleDir, "src/testFixtures/kotlin").mkdirs()
        File(moduleDir, "src/testFixtures/kotlin/FixtureFile.kt").writeText(
            "fun t() { UUID.randomUUID() }"
        )
        File(moduleDir, "src/main/kotlin").mkdirs()
        File(moduleDir, "src/main/kotlin/Main.kt").writeText("fun m() { UUID.randomUUID() }")
        val findings = NondeterminismInventory(context(src = "")).inventory()
        assertEquals(1, findings.size)
        assertTrue(findings[0].file.endsWith("src/main/kotlin/Main.kt"))
    }
}
