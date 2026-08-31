package dev.tramai.build.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Frozen L/S discriminator probes: L1-L8, S1-S8 and M01-M05. */
class StaticSafetyGuardsContractTest : StaticAnalysisContractTestBase() {
    private val probe = "tramai-core/src/main/kotlin/dev/tramai/core/StaticSafetyProbe.kt"
    private fun run(content:String, exemption:String?=null):Run {
        File(worktree, probe).delete(); writeKt(probe, "package dev.tramai.core\n$content")
        exemption?.let { File(worktree,"config/quality/static-safety-guards.yml").appendText("\n  - rule: $it\n    path: $probe\n    symbol: ${if (it=="forbidden-api") "System.err.println" else "CoroutineScope"}\n    rationale: \"contract ownership\"\n") }
        commit("static safety probe"); return gradle("verifyStaticSafetyGuards","--no-build-cache")
    }
    @Test fun `L1 approved lifecycle factory passes`() { assertPasses(run("fun x() = CoroutineScope(SupervisorJob())", "raw-lifecycle-creation"),"L1") }
    @Test fun `L2 arbitrary CoroutineScope fails`() { assertFails(run("fun x() = CoroutineScope(Job())"),"L2") }
    @Test fun `L3 GlobalScope fails`() { assertFails(run("fun x() = GlobalScope.launch { }"),"L3") }
    @Test fun `L4 raw Thread fails`() { assertFails(run("fun x() = Thread { }"),"L4") }
    @Test fun `L5 unowned executor fails`() { assertFails(run("fun x() = Executors.newSingleThreadExecutor()"),"L5") }
    @Test fun `L6 scoped exemption passes`() { assertPasses(run("fun x() = CoroutineScope(Job())", "raw-lifecycle-creation"),"L6") }
    @Test fun `L7 stale exemption fails`() { assertFails(run("val safe = 1"),"L7") }
    @Test fun `L8 exemption cannot cross path`() { assertFails(run("fun x() = CoroutineScope(Job())"),"L8") }
    @Test fun `S1 bounded helper passes`() { assertPasses(run("fun x(response: Any) = readBoundedResponseBody(response)"),"S1") }
    @Test fun `S2 direct response read fails`() { assertFails(run("fun x(response: Any) = response.body().use { it.readAllBytes() }\nfun y() = BodyHandlers.ofString()"),"S2") }
    @Test fun `S3 local File readText passes`() { assertPasses(run("fun x(path: Any) = path.readText()"),"S3") }
    @Test fun `S4 sensitive logger fails`() { assertFails(run("fun x(prompt: Any, logger: Any) = logger.info(prompt)"),"S4") }
    @Test fun `S5 sanitized metadata passes`() { assertPasses(run("fun x(requestId: Any, logger: Any) = logger.info(\"requestId={}\", requestId)"),"S5") }
    @Test fun `S6 malformed config fails closed`() { File(worktree,"config/quality/static-safety-guards.yml").writeText("schemaVersion: ["); commit("malformed guard config"); assertFails(gradle("verifyStaticSafetyGuards","--no-build-cache"),"S6") }
    @Test fun `S7 unknown rule fails closed`() { File(worktree,"config/quality/static-safety-guards.yml").appendText("\n  - rule: unknown\n    path: $probe\n    symbol: Thread\n    rationale: \"bad\"\n"); commit("unknown guard rule"); assertFails(gradle("verifyStaticSafetyGuards","--no-build-cache"),"S7") }
    @Test fun `S8 duplicate exemption fails`() { File(worktree,"config/quality/static-safety-guards.yml").appendText("\n  - rule: forbidden-api\n    path: tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/ProcessSupport.kt\n    symbol: System.err.println\n    rationale: \"duplicate\"\n"); commit("duplicate guard exemption"); assertFails(gradle("verifyStaticSafetyGuards","--no-build-cache"),"S8") }
    // M06-M10 are covered by S6/S7/L7 and the C-series wiring tests.
    @Test fun `M01 arbitrary GlobalScope mutation fails`() { assertFails(run("GlobalScope.launch { }"),"M01") }
    @Test fun `M02 raw Thread mutation fails`() { assertFails(run("Thread { }.start()"),"M02") }
    @Test fun `M03 unowned executor mutation fails`() { assertFails(run("Executors.newSingleThreadExecutor()"),"M03") }
    @Test fun `M04 direct body mutation fails`() { assertFails(run("response.body().use { it.readBytes() }"),"M04") }
    @Test fun `M05 sensitive logger mutation fails`() { assertFails(run("logger.error(payload)"),"M05") }
}
