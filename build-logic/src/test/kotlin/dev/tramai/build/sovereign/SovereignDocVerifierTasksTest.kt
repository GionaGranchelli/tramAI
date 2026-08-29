package dev.tramai.build.sovereign

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Discriminating TestKit tests for the 9.2d-a3b1 sovereign doc verifier typed
 * tasks (verifySovereignRuntimeApiBoundary, verifySovereignOpsObservabilityDocs,
 * verifySovereignRuntimeClosureDocs).
 *
 * Standing rule (user-mandated): NO empty fixture may serve as the successful
 * oracle when the verifier must discover real content. Fixtures copy REAL repo
 * files via git ls-files; positives assert SUCCESS on fresh dirs; fail-closed
 * negatives mutate exactly one real file and assert the exact diagnostic.
 *
 * Note: configuration-cache cold→warm reuse is intentionally NOT asserted here
 * (TestKit + CC is flaky); the C3-closure proof is run on the real repo by the
 * orchestrator (`--configuration-cache` cold, then warm, expecting "entry
 * reused").
 */
class SovereignDocVerifierTasksTest {

    @TempDir
    lateinit var tempDir: File

    private val repoRoot: File by lazy {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, ".git").isDirectory) {
            dir = dir.parentFile ?: error("repo root not found from ${System.getProperty("user.dir")}")
        }
        dir
    }

    private fun copyFromRepo(dir: File, vararg relativePaths: String) {
        val git = ProcessBuilder("git", "-C", repoRoot.absolutePath, "ls-files", *relativePaths)
            .redirectErrorStream(true)
            .start()
        val listing = git.inputStream.bufferedReader().readText()
        check(git.waitFor() == 0) { "git ls-files failed: $listing" }
        listing.lineSequence().filter { it.isNotBlank() }.forEach { rel ->
            val src = File(repoRoot, rel)
            val dst = File(dir, rel)
            if (src.isFile) {
                dst.parentFile.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }
    }

    private fun fixture(): File {
        val dir = File(tempDir, "fixture-${System.nanoTime()}").apply { mkdirs() }
        writeFile(dir, "settings.gradle.kts", "rootProject.name = \"sovereign-fixture\"\n")
        writeFile(dir, "build.gradle.kts", "plugins { id(\"tramai.sovereign-verification\") }\n")
        return dir
    }

    private fun runner(dir: File, vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withGradleVersion("9.0.0")
            .withArguments(*args, "--no-build-cache", "--stacktrace")
            .withPluginClasspath()

    private fun writeFile(base: File, relativePath: String, content: String) {
        val target = File(base, relativePath)
        target.parentFile.mkdirs()
        target.writeText(content)
    }

    private fun runTask(dir: File, task: String): org.gradle.testkit.runner.BuildResult {
        val result = runner(dir, task).build()
        assertTrue(
            result.task(":$task")?.outcome == TaskOutcome.SUCCESS,
            "$task must succeed: ${result.output.take(1200)}"
        )
        return result
    }

    // ------------------------------------------------------------------
    // verifySovereignRuntimeApiBoundary
    // ------------------------------------------------------------------

    @Test
    fun `verifySovereignRuntimeApiBoundary passes on real repo docs`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "docs/architecture/sovereign-api-stability-manifest.yml",
            "docs/architecture/sovereign-api-stability-boundary.md",
            "docs/STATUS.md",
            "tramai-core/src/main/kotlin/dev/tramai/core/workflow/ApprovalRequestWorkflowResultMappers.kt",
            "tramai-core/src/main/kotlin/dev/tramai/core/workflow/ApprovalWorkflowResults.kt",
            "tramai-core/src/main/kotlin/dev/tramai/core/approval/ApprovalStore.kt",
            "tramai-engine/src/main/kotlin/dev/tramai/engine/SuspendedInvocationStore.kt",
            "tramai-core/src/main/kotlin/dev/tramai/core/approval/ApprovalContinuationStore.kt",
            "tramai-security/src/main/kotlin/dev/tramai/security/audit/AuditStore.kt",
            "tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/outbox/SovereignOpsAuditOutboxStore.kt",
            "tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/outbox/SovereignOpsApprovalMutationStore.kt",
            "tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/lease/SovereignOpsWorkerLeaseStore.kt",
            "README.md",
        )
        runTask(dir, "verifySovereignRuntimeApiBoundary")
    }

    @Test
    fun `verifySovereignRuntimeApiBoundary fails when stable type removed from boundary doc`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "docs/architecture/sovereign-api-stability-manifest.yml",
            "docs/architecture/sovereign-api-stability-boundary.md",
            "docs/STATUS.md",
            "tramai-core/src/main/kotlin/dev/tramai/core/workflow/ApprovalRequestWorkflowResultMappers.kt",
            "tramai-core/src/main/kotlin/dev/tramai/core/workflow/ApprovalWorkflowResults.kt",
            "tramai-core/src/main/kotlin/dev/tramai/core/approval/ApprovalStore.kt",
            "tramai-engine/src/main/kotlin/dev/tramai/engine/SuspendedInvocationStore.kt",
            "tramai-core/src/main/kotlin/dev/tramai/core/approval/ApprovalContinuationStore.kt",
            "tramai-security/src/main/kotlin/dev/tramai/security/audit/AuditStore.kt",
            "tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/outbox/SovereignOpsAuditOutboxStore.kt",
            "tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/outbox/SovereignOpsApprovalMutationStore.kt",
            "tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/lease/SovereignOpsWorkerLeaseStore.kt",
            "README.md",
        )
        val boundary = File(dir, "docs/architecture/sovereign-api-stability-boundary.md")
        boundary.writeText(boundary.readText().replace("SovereignOpsWorkerLeaseStore", "SovereignOpsLeaseStore"))
        val result = runner(dir, "verifySovereignRuntimeApiBoundary").buildAndFail()
        assertContains(result.output, "RC+ Stable section must document SovereignOpsWorkerLeaseStore")
    }

    // ------------------------------------------------------------------
    // verifySovereignOpsObservabilityDocs
    // ------------------------------------------------------------------

    @Test
    fun `verifySovereignOpsObservabilityDocs passes on real repo docs`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "docs/operations/sovereign-ops-worker-observability-runbook.md",
            "docs/operations/prometheus/sovereign-ops-worker-promql.md",
            "docs/operations/prometheus/sovereign-ops-worker-alerts.example.yml",
            "tramai-spring-boot-starter-sovereign-ops-actuator/README.md",
            "tramai-spring-boot-starter-sovereign-ops-micrometer/README.md",
            "tramai-spring-boot-starter-sovereign-ops-observability/README.md",
        )
        runTask(dir, "verifySovereignOpsObservabilityDocs")
    }

    @Test
    fun `verifySovereignOpsObservabilityDocs fails when metric name removed from runbook`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "docs/operations/sovereign-ops-worker-observability-runbook.md",
            "docs/operations/prometheus/sovereign-ops-worker-promql.md",
            "docs/operations/prometheus/sovereign-ops-worker-alerts.example.yml",
            "tramai-spring-boot-starter-sovereign-ops-actuator/README.md",
            "tramai-spring-boot-starter-sovereign-ops-micrometer/README.md",
            "tramai-spring-boot-starter-sovereign-ops-observability/README.md",
        )
        // Dotted Micrometer name appears ONLY in the runbook — removing it from
        // the runbook must fail even though the PromQL reference still has the
        // underscore-named metric.
        val runbook = File(dir, "docs/operations/sovereign-ops-worker-observability-runbook.md")
        runbook.writeText(runbook.readText().replace("tramai.sovereign.ops.outbox.worker.cycles", "tramai.sovereign.ops.outbox.worker.cyclez"))
        val result = runner(dir, "verifySovereignOpsObservabilityDocs").buildAndFail()
        assertContains(result.output, "Expected sovereign ops observability docs to contain: tramai.sovereign.ops.outbox.worker.cycles")
    }

    // ------------------------------------------------------------------
    // verifySovereignRuntimeClosureDocs
    // ------------------------------------------------------------------

    @Test
    fun `verifySovereignRuntimeClosureDocs passes on real repo docs`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "docs/releases/sovereign-runtime-closure-boundary.md",
            "docs/releases/sovereign-runtime-rc-boundary.md",
            "docs/STATUS.md",
            "docs/architecture/sovereign-api-stability-boundary.md",
            "CHANGELOG.md",
            "docs/guides/sovereign-runtime-quickstart.md",
            "docs/runbooks/sovereign-jdbc-production-deployment.md",
            "docs/observability/prometheus-approved-resume-worker-alerts.yml",
            "docs/observability/grafana-approved-resume-worker-dashboard.json",
            "docs/runbooks/approved-resume-worker-observability.md",
            "docs/guides/approval-gateway-golden-path.md",
            "tramai-core/src/test/kotlin/dev/tramai/core/workflow/ApprovalGatewayGoldenPathErgonomicsTest.kt",
            "examples/spring-sovereign-starter/src/test/kotlin/dev/tramai/examples/spring/ApprovalGatewaySpringGoldenPathSmokeTest.kt",
            "examples/spring-sovereign-starter/src/test/kotlin/dev/tramai/examples/spring/RegulatedClaimTriageApprovalGatewayRequestFactory.kt",
            "tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/ApprovalGatewayAutoConfiguration.kt",
            "docs/architecture/human-approval-workflow-ergonomics.md",
            "tramai-core/src/test/java/dev/tramai/core/workflow/ApprovalRequestWorkflowResultMappersJavaInteropTest.java",
        )
        runTask(dir, "verifySovereignRuntimeClosureDocs")
    }

    @Test
    fun `verifySovereignRuntimeClosureDocs fails when required phrase removed`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "docs/releases/sovereign-runtime-closure-boundary.md",
            "docs/releases/sovereign-runtime-rc-boundary.md",
            "docs/STATUS.md",
            "docs/architecture/sovereign-api-stability-boundary.md",
            "CHANGELOG.md",
            "docs/guides/sovereign-runtime-quickstart.md",
            "docs/runbooks/sovereign-jdbc-production-deployment.md",
            "docs/observability/prometheus-approved-resume-worker-alerts.yml",
            "docs/observability/grafana-approved-resume-worker-dashboard.json",
            "docs/runbooks/approved-resume-worker-observability.md",
            "docs/guides/approval-gateway-golden-path.md",
            "tramai-core/src/test/kotlin/dev/tramai/core/workflow/ApprovalGatewayGoldenPathErgonomicsTest.kt",
            "examples/spring-sovereign-starter/src/test/kotlin/dev/tramai/examples/spring/ApprovalGatewaySpringGoldenPathSmokeTest.kt",
            "examples/spring-sovereign-starter/src/test/kotlin/dev/tramai/examples/spring/RegulatedClaimTriageApprovalGatewayRequestFactory.kt",
            "tramai-spring-boot-starter-sovereign-ops/src/main/kotlin/dev/tramai/spring/sovereign/ops/ApprovalGatewayAutoConfiguration.kt",
            "docs/architecture/human-approval-workflow-ergonomics.md",
            "tramai-core/src/test/java/dev/tramai/core/workflow/ApprovalRequestWorkflowResultMappersJavaInteropTest.java",
        )
        // "Sovereign JDBC Production Deployment Runbook" appears exactly once in
        // the closure doc; removing it must trip the required-phrases guard.
        val closureDoc = File(dir, "docs/releases/sovereign-runtime-closure-boundary.md")
        closureDoc.writeText(closureDoc.readText().replace("Sovereign JDBC Production Deployment Runbook", "Sovereign JDBC Deployment Runbook"))
        val result = runner(dir, "verifySovereignRuntimeClosureDocs").buildAndFail()
        assertContains(result.output, "Sovereign Runtime closure boundary is missing required phrase: Sovereign JDBC Production Deployment Runbook")
    }
}
