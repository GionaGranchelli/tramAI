package dev.tramai.build.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StaticSafetyGuardsModelTest {
    private val root get() = File(System.getProperty("tramai.repositoryRoot"))

    private fun parse(yaml: String) = StaticSafetyGuardConfigParser.parse(yaml, root)

    private fun fails(yaml: String) {
        assertFailsWith<IllegalArgumentException> { parse(yaml) }
    }

    private fun base(extra: String = "") =
        "schemaVersion: 1\n" +
            "rules:\n" +
            "  - id: x\n" +
            "    match: call-name\n" +
            "    symbols: [Thread]\n" +
            "exemptions:\n" +
            extra

    private fun exemption(
        path: String = "tramai-core/src/main/kotlin/dev/tramai/core/provider/ProviderRegistry.kt",
        symbol: String = "Thread",
        occurrences: Int = 1,
        rationale: String = "owned",
    ) = "  - rule: x\n" +
        "    path: $path\n" +
        "    symbol: $symbol\n" +
        "    occurrences: $occurrences\n" +
        "    rationale: \"$rationale\"\n"

    @Test
    fun `valid config parses`() {
        assertEquals(1, parse(base(exemption())).schemaVersion)
    }

    @Test
    fun `malformed yaml fails`() {
        fails("schemaVersion: [")
    }

    @Test
    fun `wrong schema fails`() {
        fails(base(exemption()).replace("schemaVersion: 1", "schemaVersion: 2"))
    }

    @Test
    fun `unknown exemption rule fails`() {
        fails(base(exemption()).replace("rule: x", "rule: nope"))
    }

    @Test
    fun `unknown match fails`() {
        fails(base(exemption()).replace("match: call-name", "match: nope"))
    }

    @Test
    fun `missing symbols fails`() {
        fails(base(exemption()).replace("symbols: [Thread]", "symbols: []"))
    }

    @Test
    fun `missing rationale fails`() {
        fails(base(exemption()).replace("rationale: \"owned\"", "rationale: \"\""))
    }

    @Test
    fun `duplicate exemption fails`() {
        fails(base(exemption() + exemption()))
    }

    @Test
    fun `escaping path fails`() {
        fails(base(exemption("../x.kt")))
    }

    @Test
    fun `missing path fails`() {
        fails(base(exemption("tramai-core/src/main/kotlin/dev/tramai/core/nope.kt")))
    }

    @Test
    fun `test path fails`() {
        fails(base(exemption("tramai-core/src/test/kotlin/Foo.kt")))
    }

    @Test
    fun `missing approved directory fails`() {
        val rule =
            "rules:\n  - id: y\n    match: call-name\n    symbols: [Thread]\n" +
                "    approvedPaths: [missing/]\nexemptions:"
        fails(base(exemption()).replace("exemptions:", rule))
    }

    @Test
    fun `numeric symbol fails`() {
        fails(base(exemption()).replace("symbols: [Thread]", "symbols: [42]"))
    }

    @Test
    fun `unknown rule field is forward compatible`() {
        val yaml = base(exemption()).replace("symbols: [Thread]", "symbols: [Thread]\n    futureFlag: true")
        assertEquals(1, parse(yaml).schemaVersion)
    }

    @Test
    fun `missing occurrences fails`() {
        val without = exemption().lines().filterNot { it.startsWith("    occurrences:") }.joinToString("\n") + "\n"
        fails(base(without))
    }

    @Test
    fun `zero occurrences fails`() {
        fails(base(exemption(occurrences = 0)))
    }

    @Test
    fun `non-integral occurrences fails`() {
        fails(base(exemption().replace("occurrences: 1", "occurrences: 1.5")))
    }

    @Test
    fun `duplicate rule id fails`() {
        val dup =
            "schemaVersion: 1\n" +
                "rules:\n" +
                "  - id: x\n" +
                "    match: call-name\n" +
                "    symbols: [Thread]\n" +
                "  - id: x\n" +
                "    match: call-name\n" +
                "    symbols: [Thread]\n" +
                "exemptions:\n" +
                exemption()
        fails(dup)
    }
}
