package dev.tramai.build.quality

import java.io.File
import kotlin.test.Test

/** Frozen L/S/A/Q/H/G/T/M discriminator probes. Occurrence identity is token offset, not line. */
class StaticSafetyGuardsContractTest : StaticAnalysisContractTestBase() {
    private val probe = "tramai-core/src/main/kotlin/dev/tramai/core/StaticSafetyProbe.kt"

    private fun exemptionSymbol(
        rule: String,
        symbol: String,
    ): String = if (rule == "forbidden-api") "System.err.println" else symbol

    private fun run(
        content: String,
        exemption: String? = null,
        symbol: String = "CoroutineScope",
    ): Run {
        File(worktree, probe).delete()
        writeKt(probe, "package dev.tramai.core\n$content")
        exemption?.let {
            File(worktree, "config/quality/static-safety-guards.yml").appendText(
                "\n  - rule: $it\n    path: $probe\n    symbol: " +
                    exemptionSymbol(it, symbol) +
                    "\n    occurrences: 1\n    rationale: \"contract ownership\"\n",
            )
        }
        commit("static safety probe")
        return gradle("verifyStaticSafetyGuards", "--no-build-cache")
    }

    private fun appendExemption(
        rule: String,
        symbol: String,
        occurrences: Int = 1,
        path: String = probe,
    ) {
        File(worktree, "config/quality/static-safety-guards.yml").appendText(
            "\n  - rule: $rule\n    path: $path\n    symbol: $symbol\n    occurrences: $occurrences\n" +
                "    rationale: \"contract ownership\"\n",
        )
    }

    // ── L-series: lifecycle ──

    @Test
    fun `L1 approved lifecycle factory passes`() {
        File(worktree, probe).delete()
        writeKt(probe, "package dev.tramai.core\nfun x() = CoroutineScope(SupervisorJob())")
        appendExemption("raw-lifecycle-creation", "CoroutineScope")
        appendExemption("raw-lifecycle-creation", "SupervisorJob")
        commit("L1 probe")
        assertPasses(gradle("verifyStaticSafetyGuards", "--no-build-cache"), "L1")
    }

    @Test
    fun `L2 arbitrary CoroutineScope fails`() {
        assertFails(run("fun x() = CoroutineScope(Job())"), "L2")
    }

    @Test
    fun `L3 GlobalScope fails`() {
        assertFails(run("fun x() = GlobalScope.launch { }"), "L3")
    }

    @Test
    fun `L4 raw Thread fails`() {
        assertFails(run("fun x() = Thread { }"), "L4")
    }

    @Test
    fun `L5 unowned executor fails`() {
        assertFails(run("fun x() = Executors.newSingleThreadExecutor()"), "L5")
    }

    @Test
    fun `L6 scoped exemption passes`() {
        assertPasses(run("fun x() = CoroutineScope(Job())", "raw-lifecycle-creation", "CoroutineScope"), "L6")
    }

    @Test
    fun `L7 stale exemption fails`() {
        assertFails(run("val safe = 1", "raw-lifecycle-creation", "CoroutineScope"), "L7")
    }

    @Test
    fun `L8 exemption cannot cross path`() {
        File(worktree, probe).delete()
        writeKt(probe, "package dev.tramai.core\nfun x() = CoroutineScope(Job())")
        appendExemption(
            "raw-lifecycle-creation",
            "CoroutineScope",
            path = "tramai-core/src/main/kotlin/dev/tramai/core/provider/ProviderRegistry.kt",
        )
        commit("L8 probe")
        assertFails(gradle("verifyStaticSafetyGuards", "--no-build-cache"), "L8")
    }

    // ── A-series: occurrence ratchet / nested ownership / same-line identity ──

    @Test
    fun `A1 second occurrence exceeds exemption count fails`() {
        assertFails(
            run(
                "fun x() = CoroutineScope(Job())\nfun y() = CoroutineScope(Job())",
                "raw-lifecycle-creation",
                "CoroutineScope",
            ),
            "A1",
        )
    }

    @Test
    fun `A2 removal below declared count is stale and fails`() {
        assertFails(run("val safe = 1", "raw-lifecycle-creation", "CoroutineScope"), "A2")
    }

    @Test
    fun `A3 nested Executors inside exempt CoroutineScope fails`() {
        File(worktree, probe).delete()
        writeKt(
            probe,
            "package dev.tramai.core\n" +
                "fun x() = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())",
        )
        appendExemption("raw-lifecycle-creation", "CoroutineScope")
        commit("A3 probe")
        assertFails(gradle("verifyStaticSafetyGuards", "--no-build-cache"), "A3")
    }

    @Test
    fun `A4 nested SupervisorJob needs its own exemption`() {
        File(worktree, probe).delete()
        writeKt(probe, "package dev.tramai.core\nfun x() = CoroutineScope(SupervisorJob())")
        appendExemption("raw-lifecycle-creation", "CoroutineScope")
        commit("A4 probe")
        assertFails(gradle("verifyStaticSafetyGuards", "--no-build-cache"), "A4a")
        appendExemption("raw-lifecycle-creation", "SupervisorJob")
        commit("A4 exemption")
        assertPasses(gradle("verifyStaticSafetyGuards", "--no-build-cache"), "A4b")
    }

    @Test
    fun `A5 same-line duplicate occurrence exceeds count fails`() {
        assertFails(
            run(
                "fun x() = CoroutineScope(Job()); fun y() = CoroutineScope(Job())",
                "raw-lifecycle-creation",
                "CoroutineScope",
            ),
            "A5",
        )
    }

    // ── S-series: security/config ──

    @Test
    fun `S1 bounded helper passes`() {
        assertPasses(run("fun x(response: Any) = readBoundedResponseBody(response)"), "S1")
    }

    @Test
    fun `S2 direct response read fails`() {
        assertFails(
            run(
                "fun x(response: Any) = response.body().use { it.readAllBytes() }\n" +
                    "fun y() = BodyHandlers.ofString()",
            ),
            "S2",
        )
    }

    @Test
    fun `S3 local File readText passes`() {
        assertPasses(run("fun x(path: Any) = path.readText()"), "S3")
    }

    @Test
    fun `S4 sensitive logger fails`() {
        assertFails(run("fun x(prompt: Any, logger: Any) = logger.info(prompt)"), "S4")
    }

    @Test
    fun `S5 sanitized metadata passes`() {
        assertPasses(run("fun x(requestId: Any, logger: Any) = logger.info(\"requestId={}\", requestId)"), "S5")
    }

    @Test
    fun `S6 malformed config fails closed`() {
        File(worktree, "config/quality/static-safety-guards.yml").writeText("schemaVersion: [")
        commit("malformed guard config")
        assertFails(gradle("verifyStaticSafetyGuards", "--no-build-cache"), "S6")
    }

    @Test
    fun `S7 unknown rule fails closed`() {
        File(worktree, "config/quality/static-safety-guards.yml").appendText(
            "\n  - rule: unknown\n    path: $probe\n    symbol: Thread\n    occurrences: 1\n" +
                "    rationale: \"bad\"\n",
        )
        commit("unknown guard rule")
        assertFails(gradle("verifyStaticSafetyGuards", "--no-build-cache"), "S7")
    }

    @Test
    fun `S8 duplicate exemption fails`() {
        File(worktree, "config/quality/static-safety-guards.yml").appendText(
            "\n  - rule: forbidden-api\n" +
                "    path: tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/ProcessSupport.kt\n" +
                "    symbol: System.err.println\n    occurrences: 1\n    rationale: \"duplicate\"\n",
        )
        commit("duplicate guard exemption")
        assertFails(gradle("verifyStaticSafetyGuards", "--no-build-cache"), "S8")
    }

    // ── Q-series: imports / aliases / fully-qualified / Java static calls ──

    @Test
    fun `Q1 fully-qualified executor fails`() {
        assertFails(run("fun x() = java.util.concurrent.Executors.newSingleThreadExecutor()"), "Q1")
    }

    @Test
    fun `Q2 GlobalScope alias fails`() {
        assertFails(run("import kotlinx.coroutines.GlobalScope as GS\nfun x() = GS.launch { }"), "Q2")
    }

    @Test
    fun `Q3 imported executor factory fails`() {
        assertFails(run("import java.util.concurrent.Executors\nfun x() = Executors.newSingleThreadExecutor()"), "Q3")
    }

    @Test
    fun `Q4 qualified BodyHandlers fails`() {
        assertFails(run("fun x() = HttpResponse.BodyHandlers.ofString()"), "Q4")
    }

    @Test
    fun `Q5 imported BodyHandlers fails`() {
        assertFails(run("import java.net.http.HttpResponse.BodyHandlers.ofString\nfun y() = ofString()"), "Q5")
    }

    @Test
    fun `Q6 kotlin concurrent thread fails`() {
        assertFails(run("fun x() = kotlin.concurrent.thread { }"), "Q6")
    }

    @Test
    fun `Q7 java static imported executor member fails`() {
        assertFails(
            run(
                "import static java.util.concurrent.Executors.newSingleThreadExecutor;\n" +
                    "fun x() = newSingleThreadExecutor()",
            ),
            "Q7",
        )
    }

    @Test
    fun `Q8 java static wildcard executor fails`() {
        assertFails(
            run(
                "import static java.util.concurrent.Executors.*;\n" +
                    "fun x() = newSingleThreadExecutor()",
            ),
            "Q8",
        )
    }

    // ── H-series: direct unbounded body reads ──

    @Test
    fun `H1 direct readAllBytes fails`() {
        assertFails(run("fun x(response: Any) = response.body().readAllBytes()"), "H1")
    }

    @Test
    fun `H2 direct readBytes fails`() {
        assertFails(run("fun x(response: Any) = response.body().readBytes()"), "H2")
    }

    // ── G-series: sensitive logging ──

    @Test
    fun `G1 this logger receiver fails`() {
        assertFails(run("fun x(payload: Any, logger: Any) = this.logger.info(payload)"), "G1")
    }

    @Test
    fun `G2 non-logging method passes`() {
        assertPasses(run("fun x(payload: Any, logger: Any) = logger.sanitize(payload)"), "G2")
    }

    @Test
    fun `G3 trailing-lambda sensitive logging fails`() {
        assertFails(run("fun x(payload: Any, logger: Any) = logger.info { payload }"), "G3")
    }

    @Test
    fun `G4 trailing-lambda safe logging with later payload passes`() {
        assertPasses(run("fun x(requestId: Any, logger: Any) = logger.debug { requestId }\nval payload = 1"), "G4")
    }

    // ── T-series: strings and interpolation ──

    @Test
    fun `T1 raw-string fake forbidden source passes`() {
        assertPasses(run("val example = \"\"\"GlobalScope.launch { }\"\"\""), "T1")
    }

    @Test
    fun `T2 raw-string interpolation executing forbidden source fails`() {
        assertFails(run("val x = \"\"\"\${Thread { }}\"\"\""), "T2")
    }

    // ── M-series: mutations (regression) ──

    @Test
    fun `M01 arbitrary GlobalScope mutation fails`() {
        assertFails(run("GlobalScope.launch { }"), "M01")
    }

    @Test
    fun `M02 raw Thread mutation fails`() {
        assertFails(run("Thread { }.start()"), "M02")
    }

    @Test
    fun `M03 unowned executor mutation fails`() {
        assertFails(run("Executors.newSingleThreadExecutor()"), "M03")
    }

    @Test
    fun `M04 direct body mutation fails`() {
        assertFails(run("response.body().use { it.readBytes() }"), "M04")
    }

    @Test
    fun `M05 sensitive logger mutation fails`() {
        assertFails(run("logger.error(payload)"), "M05")
    }
}
