package dev.tramai.build.quality

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StaticSafetyGuardsModelTest {
    private val root get() = File(System.getProperty("tramai.repositoryRoot"))

    private fun parse(yaml: String) = StaticSafetyGuardConfigParser.parse(yaml, root)

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
        rationale: String = "owned",
    ) = "  - rule: x\n" +
        "    path: $path\n" +
        "    symbol: $symbol\n" +
        "    rationale: \"$rationale\"\n"

    @Test fun `valid config parses`() {
        assertEquals(1, parse(base(exemption())).schemaVersion)
    }

    @Test fun `malformed yaml fails`() {
        assertFailsWith<IllegalArgumentException> { parse("schemaVersion: [") }
    }

    @Test fun `wrong schema fails`() {
        assertFailsWith<IllegalArgumentException> { parse(base(exemption()).replace("schemaVersion: 1", "schemaVersion: 2")) }
    }

    @Test fun `unknown exemption rule fails`() {
        assertFailsWith<IllegalArgumentException> { parse(base(exemption()).replace("rule: x", "rule: nope")) }
    }

    @Test fun `unknown match fails`() {
        assertFailsWith<IllegalArgumentException> { parse(base(exemption()).replace("match: call-name", "match: nope")) }
    }

    @Test fun `missing symbols fails`() {
        assertFailsWith<IllegalArgumentException> { parse(base(exemption()).replace("symbols: [Thread]", "symbols: []")) }
    }

    @Test fun `missing rationale fails`() {
        assertFailsWith<IllegalArgumentException> { parse(base(exemption()).replace("rationale: \"owned\"", "rationale: \"\"")) }
    }

    @Test fun `duplicate exemption fails`() {
        assertFailsWith<IllegalArgumentException> { parse(base(exemption() + exemption())) }
    }

    @Test fun `escaping path fails`() {
        assertFailsWith<IllegalArgumentException> { parse(base(exemption("../x.kt"))) }
    }

    @Test fun `missing path fails`() {
        assertFailsWith<IllegalArgumentException> { parse(base(exemption("tramai-core/src/main/kotlin/dev/tramai/core/nope.kt"))) }
    }

    @Test fun `test path fails`() {
        assertFailsWith<IllegalArgumentException> { parse(base(exemption("tramai-core/src/test/kotlin/Foo.kt"))) }
    }

    @Test fun `missing approved directory fails`() {
        assertFailsWith<IllegalArgumentException> {
            parse(
                base(
                    exemption(),
                ).replace(
                    "exemptions:",
                    "rules:\n  - id: y\n    match: call-name\n    symbols: [Thread]\n    approvedPaths: [missing/]\nexemptions:",
                ),
            )
        }
    }

    @Test fun `numeric symbol fails`() {
        assertFailsWith<IllegalArgumentException> { parse(base(exemption()).replace("symbols: [Thread]", "symbols: [42]")) }
    }

    @Test fun `unknown rule field is forward compatible`() {
        assertEquals(1, parse(base(exemption()).replace("symbols: [Thread]", "symbols: [Thread]\n    futureFlag: true")).schemaVersion)
    }
}
