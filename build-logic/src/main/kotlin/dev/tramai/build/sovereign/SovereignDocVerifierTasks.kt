package dev.tramai.build.sovereign

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Typed CC-safe replacements for the three root build.gradle.kts doLast doc
 * verifiers (Epic 9.2d-a3b1). Each task declares the EXACT files it reads as
 * [InputFiles] (never a directory), has no project access at execution, and
 * delegates to a pure top-level verifier function. Diagnostics (require
 * messages, failure order) are byte-identical to the historical closures.
 */

/**
 * Verifies the documented Sovereign Runtime API stability boundary
 * (docs/architecture/sovereign-api-stability-manifest.yml +
 * sovereign-api-stability-boundary.md + docs/STATUS.md + stable API sources +
 * README.md). Was a C3 configuration-cache offender as a doLast closure.
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class SovereignRuntimeApiBoundaryVerifierTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val boundaryDoc: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val statusDoc: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mapperFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val javaFacadeFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stableApiFiles: ConfigurableFileCollection

    @get:Optional
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val readmeFile: RegularFileProperty

    /** Repository root — used only to render relative paths in diagnostics. */
    @get:Input
    abstract val projectDir: Property<File>

    @TaskAction
    fun verify() {
        verifySovereignRuntimeApiBoundaryDocs(
            manifestFile = manifestFile.get().asFile,
            boundaryDoc = boundaryDoc.get().asFile,
            statusDoc = statusDoc.get().asFile,
            mapperFile = mapperFile.get().asFile,
            javaFacadeFile = javaFacadeFile.get().asFile,
            stableApiFiles = stableApiFiles.files.toList(),
            readmeFile = readmeFile.get().asFile,
            projectDir = projectDir.get(),
        )
    }
}

/**
 * Validates sovereign ops worker observability docs against the expected
 * metric contract, API surface, and safe-label rules. Was a C3
 * configuration-cache offender (and declared notCompatibleWithConfigurationCache)
 * as a doLast closure; the declaration is removed by the typed conversion.
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class SovereignOpsObservabilityDocsVerifierTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runbook: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val promql: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val alerts: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val actuatorReadme: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val micrometerReadme: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val observabilityReadme: RegularFileProperty

    @TaskAction
    fun verify() {
        verifySovereignOpsObservabilityDocs(
            runbook = runbook.get().asFile,
            promql = promql.get().asFile,
            alerts = alerts.get().asFile,
            actuatorReadme = actuatorReadme.get().asFile,
            micrometerReadme = micrometerReadme.get().asFile,
            observabilityReadme = observabilityReadme.get().asFile,
        )
    }
}

/**
 * Verifies Sovereign Runtime closure documentation links and required claims
 * (docs/releases/sovereign-runtime-closure-boundary.md + the full doc/test
 * surface it cross-checks). Same family as the other two; accidental doLast
 * closure in the root build script (no notCompatible declaration).
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class SovereignRuntimeClosureDocsVerifierTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val closureDoc: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rcBoundary: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val status: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apiStabilityDoc: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val changelog: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val quickstart: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jdbcRunbook: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resumeAlerts: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resumeDashboard: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resumeRunbook: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val goldenPathGuide: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val goldenPathTest: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val springSmokeTest: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val regulatedFactoryFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val gatewayAutoConfig: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val humanApprovalErgonomics: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val javaInteropTest: RegularFileProperty

    @TaskAction
    fun verify() {
        verifySovereignRuntimeClosureDocs(
            closureDoc = closureDoc.get().asFile,
            rcBoundary = rcBoundary.get().asFile,
            status = status.get().asFile,
            apiStabilityDoc = apiStabilityDoc.get().asFile,
            changelog = changelog.get().asFile,
            quickstart = quickstart.get().asFile,
            jdbcRunbook = jdbcRunbook.get().asFile,
            resumeAlerts = resumeAlerts.get().asFile,
            resumeDashboard = resumeDashboard.get().asFile,
            resumeRunbook = resumeRunbook.get().asFile,
            goldenPathGuide = goldenPathGuide.get().asFile,
            goldenPathTest = goldenPathTest.get().asFile,
            springSmokeTest = springSmokeTest.get().asFile,
            regulatedFactoryFile = regulatedFactoryFile.get().asFile,
            gatewayAutoConfig = gatewayAutoConfig.get().asFile,
            humanApprovalErgonomics = humanApprovalErgonomics.get().asFile,
            javaInteropTest = javaInteropTest.get().asFile,
        )
    }
}

/**
 * Historical verifySovereignRuntimeApiBoundary doLast body, verbatim. All
 * require() messages and their evaluation order are preserved byte-identically.
 */
fun verifySovereignRuntimeApiBoundaryDocs(
    manifestFile: File,
    boundaryDoc: File,
    statusDoc: File,
    mapperFile: File,
    javaFacadeFile: File,
    stableApiFiles: List<File>,
    readmeFile: File,
    projectDir: File,
) {
    // ── Required files exist ──

    require(manifestFile.exists()) {
        "Missing API stability manifest at ${manifestFile.absolutePath}"
    }

    require(boundaryDoc.exists()) {
        "Missing API stability boundary document at ${boundaryDoc.absolutePath}"
    }

    require(statusDoc.exists()) {
        "Missing STATUS.md at ${statusDoc.absolutePath}"
    }

    val boundaryText = boundaryDoc.readText()
    val manifestText = manifestFile.readText()
    val statusText = statusDoc.readText()

    // ── Section scopes ──

    val stableSection = boundaryText
        .substringAfter("## RC+ Stable Surface")
        .substringBefore("## Preview Surface")

    val previewSection = boundaryText
        .substringAfter("## Preview Surface")
        .substringBefore("## Internal Implementation Details")

    val internalSection = boundaryText
        .substringAfter("## Internal Implementation Details")
        .substringBefore("## Deferred to Future Roadmaps")

    val deferredSection = boundaryText
        .substringAfter("## Deferred to Future Roadmaps")
        .substringBefore("## Compatibility Promise")

    // ── RC+ Stable types ──

    val rcPlusStableTypes = listOf(
        "ApprovalStore",
        "SuspendedInvocationStore",
        "ApprovalContinuationStore",
        "AuditStore",
        "SovereignOpsAuditOutboxStore",
        "SovereignOpsApprovalMutationStore",
        "SovereignOpsWorkerLeaseStore",
    )

    rcPlusStableTypes.forEach { type ->
        require(stableSection.contains(type)) {
            "RC+ Stable section must document $type"
        }
        require(manifestText.contains("- $type")) {
            "API stability manifest rcPlusStable.types must include $type"
        }
    }

    require(stableSection.contains("verifySovereignRuntimeClosure")) {
        "RC+ Stable section must document verifySovereignRuntimeClosure"
    }
    require(stableSection.contains("verifySovereignRuntimeReleaseCandidate")) {
        "RC+ Stable section must document verifySovereignRuntimeReleaseCandidate"
    }

    // ── Preview types (section-scoped) ──

    val previewTypes = listOf(
        "ApprovalDecisionControlPlane",
        "ApprovalResumeControlPlane",
        "ApprovalInboxQueryService",
        "REST control plane endpoints",
        "Preview reviewer UI",
        "Workflow ergonomics",
    )

    val stableManifestSection = manifestText
        .substringAfter("rcPlusStable:")
        .substringBefore("preview:")

    val previewManifestSection = manifestText
        .substringAfter("preview:")
        .substringBefore("stabilizationCandidates:")

    val previewManifestTypes = listOf(
        "ApprovalDecisionControlPlane",
        "ApprovalResumeControlPlane",
        "ApprovalInboxQueryService",
        "ApprovalGatewayAutoConfiguration",
    )

    previewTypes.forEach { type ->
        require(previewSection.contains(type, ignoreCase = true)) {
            "Preview Surface section must document '$type'"
        }
    }

    previewManifestTypes.forEach { type ->
        require(previewManifestSection.contains("- $type")) {
            "API stability manifest preview.types must include '$type' in the preview section but ${if (manifestText.contains("- $type")) "it appears outside it" else "it was not found"}."
        }
        require(!stableManifestSection.contains("- $type")) {
            "'$type' is Preview and must not appear in rcPlusStable manifest section."
        }
    }

    // ── Preview function source file exists and maintains signature ──

    require(mapperFile.exists()) {
        "Missing Preview approval workflow result mapper source file at ${mapperFile.absolutePath}"
    }

    val mapperSource = mapperFile.readText()
    require(mapperSource.contains("fun <T> ApprovalRequestResult.toWorkflowResult")) {
        "ApprovalRequestResult.toWorkflowResult mapper must remain available."
    }
    require(mapperSource.contains("HumanApprovalDecision.Approved")) {
        "ApprovalRequestResult.toWorkflowResult must expose the approved decision to the lambda."
    }
    require(mapperSource.contains("approvedValue(decision)")) {
        "ApprovalRequestResult.toWorkflowResult must pass the approved decision into approvedValue."
    }

    // ── Java facade source file exists and maintains shape ──

    require(javaFacadeFile.exists()) {
        "Missing Java approval workflow facade at ${javaFacadeFile.absolutePath}"
    }

    val javaFacadeSource = javaFacadeFile.readText()

    require(javaFacadeSource.contains("@file:JvmName(\"ApprovalWorkflowResults\")")) {
        "Java facade must keep stable JVM entrypoint name ApprovalWorkflowResults."
    }

    require(javaFacadeSource.contains("fun <T> fromApprovalRequestResult(")) {
        "Java facade must expose fromApprovalRequestResult."
    }

    require(javaFacadeSource.contains("fun suspended(") &&
            javaFacadeSource.contains("approvalId: String") &&
            javaFacadeSource.contains("workflowRunId: String")) {
        "ApprovalRequestResults.suspended must remain String-based for Java interop."
    }

    require(javaFacadeSource.contains("@JvmOverloads") &&
            javaFacadeSource.contains("fun approved(") &&
            javaFacadeSource.contains("fun denied(")) {
        "HumanApprovalDecisions approved/denied must retain @JvmOverloads for Java callers."
    }

    require(!javaFacadeSource.contains("object ApprovalIds")) {
        "Do not expose inline-value-class-returning ApprovalIds facade; Java must use String-based factories."
    }

    // ── Promoted APIs in RC+ Stable section, Preview surfaces stay out ──

    require(stableSection.contains("ApprovalRequestResult.toWorkflowResult")) {
        "ApprovalRequestResult.toWorkflowResult is now RC+ Stable and must be documented in the RC+ Stable section."
    }

    require(stableSection.contains("ApprovalWorkflowResults")) {
        "ApprovalWorkflowResults is now RC+ Stable and must be documented in the RC+ Stable section."
    }

    require(!stableSection.contains("DefaultApprovalGateway") && !stableSection.contains("ApprovalGatewayAutoConfiguration")) {
        "DefaultApprovalGateway and ApprovalGatewayAutoConfiguration are Preview and must not appear in the RC+ Stable section."
    }

    // ── Internal implementation details stay internal (section-scoped) ──

    val internalTypes = listOf(
        "JdbcApprovalStore",
        "JdbcApprovalResumeCredentialStore",
        "ApprovedContinuationResumeQueue",
        "SovereignOpsApprovedContinuationResumeWorker",
        "ApprovedContinuationResumeWorkerMetricsObserver",
        "ApprovedResumeQueueMetricsSnapshotProvider",
    )

    internalTypes.forEach { type ->
        require(internalSection.contains(type)) {
            "Internal Implementation Details section must document '$type'"
        }
        require(manifestText.contains("- $type")) {
            "API stability manifest internal.types must include $type"
        }
    }

    // ── Deferred capabilities stay deferred (section-scoped) ──

    val deferredCapabilities = listOf(
        "Key rotation",
        "Production certification",
        "Production-grade reviewer UI",
        "Enterprise IAM",
        "Maven Central release",
        "Stable 1.0 API",
    )

    deferredCapabilities.forEach { cap ->
        require(deferredSection.contains(cap, ignoreCase = true)) {
            "Deferred to Future Roadmaps section must document '$cap'"
        }
        require(manifestText.contains("- $cap", ignoreCase = true)) {
            "API stability manifest deferred.capabilities must include '$cap'"
        }
    }

    // ── Stable API source files exist ──

    stableApiFiles.forEach { sourceFile ->
        require(sourceFile.exists()) {
            "Stable API source file missing: ${sourceFile.relativeTo(projectDir).path}"
        }
    }

    // ── Forbidden: internal implementation classes in RC+ Stable section ──

    val internalJdbcClasses = listOf(
        "JdbcApprovalStore",
        "JdbcApprovalResumeCredentialStore",
        "SovereignOpsApprovedContinuationResumeWorker",
        "ApprovedResumeQueueMetricsSnapshotProvider",
    )

    internalJdbcClasses.forEach { clazz ->
        require(!stableSection.contains(clazz)) {
            "Internal JDBC/worker class '$clazz' must not appear in the RC+ Stable section"
        }
    }

    // ── Forbidden: positive overclaims in STATUS and README ──
    // Uses regex patterns that match affirmative claims but not safe
    // negated disclaimers like "not GA-certified" or "not production-certified".

    val statusAndReadmeText = StringBuilder(statusText)
    if (readmeFile.exists()) {
        statusAndReadmeText.append("\n").append(readmeFile.readText())
    }
    val combinedText = statusAndReadmeText.toString()

    val forbiddenOverclaimPatterns = listOf(
        Regex("\\bis\\s+GA-certified\\b", RegexOption.IGNORE_CASE),
        Regex("\\bproduction\\s+certified\\b", RegexOption.IGNORE_CASE),
        Regex("\\bstable\\s+1\\.0\\s+public\\s+API\\s+complete\\b", RegexOption.IGNORE_CASE),
        Regex("\\bMaven\\s+Central\\s+release\\s+complete\\b", RegexOption.IGNORE_CASE),
        Regex("\\benterprise\\s+IAM\\s+complete\\b", RegexOption.IGNORE_CASE),
        Regex("\\bkey\\s+rotation\\s+complete\\b", RegexOption.IGNORE_CASE),
        Regex("\\bproduction\\-grade\\s+reviewer\\s+UI\\s+complete\\b", RegexOption.IGNORE_CASE),
    )

    forbiddenOverclaimPatterns.forEach { pattern ->
        require(!combinedText.contains(pattern)) {
            "Forbidden affirmative overclaim pattern found: '${pattern.pattern}'. " +
                "API boundary docs must not claim GA/production/Maven/key-rotation completion. " +
                "Safe negated forms (e.g. 'not GA-certified') are permitted."
        }
    }

    // ── Promoted approval workflow APIs ──

    val stabilizationCandidateSection = manifestText
        .substringAfter("stabilizationCandidates:")
        .substringBefore("internal:")

    val promotedApprovalWorkflowTypes = listOf(
        "ApprovalGateway",
        "ApprovalRequestResult",
        "SovereignWorkflowResult",
        "ApprovalWorkflowResults",
        "ApprovalRequestResults",
        "HumanApprovalDecisions",
    )

    promotedApprovalWorkflowTypes.forEach { type ->
        require(stableManifestSection.contains("- $type")) {
            "Promoted approval workflow API '$type' must be listed in rcPlusStable manifest section."
        }
        require(!previewManifestSection.contains("- $type\n")) {
            "Promoted approval workflow API '$type' must not remain in Preview manifest section."
        }
        require(!stabilizationCandidateSection.contains("- $type")) {
            "Promoted approval workflow API '$type' must not remain in stabilizationCandidates."
        }
    }

    val promotedApprovalWorkflowFunctions = listOf(
        "ApprovalRequestResult.toWorkflowResult",
        "ApprovalWorkflowResults.fromApprovalRequestResult",
    )

    promotedApprovalWorkflowFunctions.forEach { func ->
        require(stableManifestSection.contains("- $func")) {
            "Promoted approval workflow function '$func' must be listed in rcPlusStable manifest section."
        }
        require(!previewManifestSection.contains("- $func")) {
            "Promoted approval workflow function '$func' must not remain in Preview manifest section."
        }
        require(!stabilizationCandidateSection.contains("- $func")) {
            "Promoted approval workflow function '$func' must not remain in stabilizationCandidates."
        }
    }

    // ── Non-promoted surfaces must remain Preview ──

    val stillPreviewApprovalSurfaces = listOf(
        "ApprovalDecisionControlPlane",
        "ApprovalResumeControlPlane",
        "ApprovalInboxQueryService",
        "ApprovalGatewayAutoConfiguration",
    )

    stillPreviewApprovalSurfaces.forEach { type ->
        require(previewManifestSection.contains("- $type")) {
            "'$type' must remain Preview."
        }
        require(!stableManifestSection.contains("- $type")) {
            "'$type' must not be promoted to RC+ Stable."
        }
    }

    // ── STATUS.md must reference the API stability boundary ──

    require(statusText.contains("Sovereign Runtime API Stability")) {
        "STATUS.md must reference Sovereign Runtime API Stability section"
    }
}

/**
 * Historical verifySovereignOpsObservabilityDocs doLast body, verbatim.
 * All require() messages and their evaluation order are preserved byte-identically.
 */
fun verifySovereignOpsObservabilityDocs(
    runbook: File,
    promql: File,
    alerts: File,
    actuatorReadme: File,
    micrometerReadme: File,
    observabilityReadme: File,
) {
    val files = listOf(runbook, promql, alerts)
    files.forEach {
        require(it.isFile) {
            // Historical root script rendered this via File.invariantSeparatorsPath;
            // on this platform (Linux, '/' separator) File.path is byte-identical.
            "Missing required observability doc: ${it.path}"
        }
    }

    val runbookText = runbook.readText()
    val allText = files.joinToString("\n") { it.readText() }

    fun requireContains(value: String) {
        require(value in allText) {
            "Expected sovereign ops observability docs to contain: $value"
        }
    }

    fun requireAbsent(value: String) {
        require(value !in allText) {
            "Forbidden content found in sovereign ops observability docs: $value"
        }
    }

    // ── A. Required Prometheus metric names
    listOf(
        "tramai_sovereign_ops_outbox_worker_cycles_total",
        "tramai_sovereign_ops_outbox_worker_duration_seconds_count",
        "tramai_sovereign_ops_outbox_worker_duration_seconds_sum",
        "tramai_sovereign_ops_outbox_worker_duration_seconds_max",
        "tramai_sovereign_ops_outbox_worker_failures_total",
        "tramai_sovereign_ops_outbox_worker_recovered_records_total",
        "tramai_sovereign_ops_outbox_worker_dispatched_records_total",
    ).forEach(::requireContains)

    // ── A. Required dotted Micrometer names
    listOf(
        "tramai.sovereign.ops.outbox.worker.cycles",
        "tramai.sovereign.ops.outbox.worker.duration",
        "tramai.sovereign.ops.outbox.worker.failures",
        "tramai.sovereign.ops.outbox.worker.recovered.records",
        "tramai.sovereign.ops.outbox.worker.dispatched.records",
    ).forEach(::requireContains)

    // ── B. Typo guard
    requireAbsent("tamai_")

    // ── C. Forbidden sensitive labels in PromQL/YAML selector context
    val selectorRegex = Regex(
        """\{(?:[^}]*\b(?:tenant_id|user_id|document_id|approval_id|workflow_id|correlation_id|token|prompt|model_response|file_path|stack_trace)\b[^}]*)\}"""
    )
    val hasForbiddenLabels = allText.contains(selectorRegex)
    require(!hasForbiddenLabels) {
        "Forbidden sensitive label found in PromQL/alert selector context"
    }

    // ── D. Approved safe label names present
    listOf("outcome", "failure_action", "error_type", "result")
        .forEach(::requireContains)

    // ── E. Real observer API documented, invalid API absent
    requireContains("onCycleCompleted(summary")
    requireContains("onCycleFailed(action")
    requireAbsent("onStatus(")
    // ── F. Real Actuator snapshot fields present, made-up fields absent
    listOf(
        "enabled", "running", "recoverPreparedEnabled", "dispatchPendingEnabled",
        "batchSize", "intervalMillis", "lastCycleDurationMillis",
        "lastRecovered", "lastDispatched", "lastFailure", "lastFailureAt",
        "totalCyclesCompleted", "totalCyclesFailed",
    ).forEach { field ->
        require(field in runbookText) {
            "Runbook is missing real Actuator snapshot field: $field"
        }
    }

    listOf(
        "lastCycleResult", "cyclesSinceLastReset", "totalCycleDurationMillis",
        "recoveredCount", "dispatchedCount", "failureCount",
    ).forEach { field ->
        require(field !in runbookText) {
            "Runbook contains stale made-up Actuator field: $field"
        }
    }

    // ── G. OpenTelemetry exporter wording
    requireContains("does not")
    requireContains("bring an SDK or exporter")
    requireContains("must provide their own OpenTelemetry SDK and exporter configuration")

    // ── H. Alert warning guard
    val alertText = alerts.readText()
    require("WARNING" in alertText) {
        "Alert examples must contain a WARNING header"
    }
    require("NOT production defaults" in alertText) {
        "Alert examples must state they are NOT production defaults"
    }
    require("Thresholds must be tuned" in alertText) {
        "Alert examples must state that thresholds must be tuned"
    }

    // ── J. Health indicator documentation guard
    requireContains("tramai.sovereign.ops.actuator.worker-health.enabled=true")
    requireContains("tramaiSovereignOpsWorkerHealthIndicator")
    requireContains("tramaiSovereignOpsWorker")
    requireContains("Health component name")

    // ── I. Starter README link guard
    val runbookRef = "sovereign-ops-worker-observability-runbook.md"
    val promqlRef = "sovereign-ops-worker-promql.md"

    require(runbookRef in actuatorReadme.readText()) {
        "Actuator README must link to the observability runbook"
    }
    require(runbookRef in micrometerReadme.readText()) {
        "Micrometer README must link to the observability runbook"
    }
    require(promqlRef in micrometerReadme.readText()) {
        "Micrometer README must link to the PromQL reference"
    }
    require(runbookRef in observabilityReadme.readText()) {
        "OpenTelemetry README must link to the observability runbook"
    }
}

/**
 * Historical verifySovereignRuntimeClosureDocs doLast body, verbatim.
 * All require() messages and their evaluation order are preserved byte-identically.
 */
fun verifySovereignRuntimeClosureDocs(
    closureDoc: File,
    rcBoundary: File,
    status: File,
    apiStabilityDoc: File,
    changelog: File,
    quickstart: File,
    jdbcRunbook: File,
    resumeAlerts: File,
    resumeDashboard: File,
    resumeRunbook: File,
    goldenPathGuide: File,
    goldenPathTest: File,
    springSmokeTest: File,
    regulatedFactoryFile: File,
    gatewayAutoConfig: File,
    humanApprovalErgonomics: File,
    javaInteropTest: File,
) {
    require(closureDoc.exists()) {
        "Missing Sovereign Runtime closure boundary document at ${closureDoc.absolutePath}."
    }

    val closureText = closureDoc.readText()

    val requiredPhrases = listOf(
        "RC+ / enterprise proof",
        "not a GA-certified production release",
        "Key rotation",
        "verifySovereignRuntimeReleaseCandidate",
        ":examples:spring-sovereign-starter:e2eTest",
        "Regulated Claim Triage",
        "Sovereign JDBC Production Deployment Runbook",
        // ── Included preview/preview surfaces (positive checks) ──
        "Preview reviewer UI",
        "Approved-resume lifecycle JDBC E2E proof",
        "Approved-continuation auto-resume worker",
        "Internal encrypted resume credential custody",
        "queue snapshot",
        "Micrometer",
    )

    requiredPhrases.forEach { phrase ->
        require(closureText.contains(phrase)) {
            "Sovereign Runtime closure boundary is missing required phrase: $phrase"
        }
    }

    // Verify that GA is explicitly not claimed — positive check above already
    // requires "not a GA-certified production release". These negative guards
    // prevent accidental overclaiming if the document is later edited.
    // Note: "stable 1.0 API" appears legitimately in the non-goals section,
    // so we only guard against affirmative claims.
    val forbiddenClaims = listOf(
        "is GA-certified",
        "production certified",
    )

    forbiddenClaims.forEach { forbidden ->
        require(!closureText.contains(forbidden, ignoreCase = true)) {
            "Sovereign Runtime closure boundary must not claim: $forbidden"
        }
    }

    // Key rotation must be explicitly deferred, not merely mentioned
    require(closureText.contains("deferred", ignoreCase = true)) {
        "Closure boundary must explicitly defer key rotation (found 'Key rotation' but not 'deferred')."
    }

    // ── Negative checks: prevent stale deferred claims about items now included ──
    // These items were added as preview/internal surfaces during post-closure PRs.
    // They must NOT appear as bare deferred items without proper context.
    // Context-aware: verify the "Production-grade" qualified versions exist (they're
    // in the Explicit Non-Goals section) rather than checking for bare "- Reviewer UI", etc.

    require(closureText.contains("Production-grade reviewer UI")) {
        "Closure boundary must include 'Production-grade reviewer UI' in non-goals (ensures " +
            "reviewer UI is not listed as a bare deferred item without context)."
    }

    require(closureText.contains("Production-grade admin REST surface")) {
        "Closure boundary must include 'Production-grade admin REST surface' in non-goals (ensures " +
            "REST control plane is not listed as a bare deferred item without context)."
    }

    // Ensure no stale section headers exist that would indicate these items were
    // moved out of Included Capabilities into deferred/planned buckets
    val staleSectionHeaders = listOf(
        "Deferred from Closure",
        "Planned / Not Complete",
    )

    staleSectionHeaders.forEach { staleHeader ->
        require(!closureText.contains(staleHeader, ignoreCase = true)) {
            "Closure boundary must not contain stale section header: '$staleHeader'. " +
                "Items like Reviewer UI, REST control plane, and operational endpoints " +
                "are already included as preview surfaces."
        }
    }

    require(rcBoundary.readText().contains("sovereign-runtime-closure-boundary.md")) {
        "RC boundary must link to the closure boundary."
    }

    val statusText = status.readText()
    require(statusText.contains("Sovereign Runtime Closure Status")) {
        "docs/STATUS.md must include Sovereign Runtime Closure Status section."
    }

    // ── API stability boundary ──

    require(apiStabilityDoc.exists()) {
        "Missing Sovereign Runtime API stability boundary document at ${apiStabilityDoc.absolutePath}."
    }

    val apiStabilityText = apiStabilityDoc.readText()

    val requiredApiStabilityPhrases = listOf(
        "RC+ Stable",
        "Preview",
        "Internal",
        "Deferred",
        "ApprovalStore",
        "SuspendedInvocationStore",
        "ApprovalContinuationStore",
        "AuditStore",
        "SovereignOpsAuditOutboxStore",
        "SovereignOpsApprovalMutationStore",
        "SovereignOpsWorkerLeaseStore",
        "concrete JDBC store implementations",
        "workflow ergonomics",
        "key rotation",
        "not a GA-certified production release",
    )

    requiredApiStabilityPhrases.forEach { phrase ->
        require(apiStabilityText.contains(phrase, ignoreCase = true)) {
            "Sovereign Runtime API stability boundary is missing required phrase: $phrase"
        }
    }

    // Closure boundary must link to the API stability boundary
    require(closureText.contains("sovereign-api-stability-boundary.md")) {
        "Closure boundary must link to the Sovereign Runtime API stability boundary."
    }

    // STATUS.md must mention the API stability boundary
    require(statusText.contains("Sovereign Runtime API Stability")) {
        "STATUS.md must include Sovereign Runtime API Stability section."
    }

    // ── Docs consistency checks for PR #118 review findings ──
    // These prevent re-introduction of incorrect names, statuses, and patterns
    // that were fixed during the PR #118 docs review cycle.

    val changelogText = changelog.readText()
    val quickstartText = quickstart.readText()
    val jdbcRunbookText = jdbcRunbook.readText()
    val allDocs = changelogText + "\n" + quickstartText + "\n" + jdbcRunbookText

    // Forbidden: nested YAML form of rest-control-plane-enabled (history: quickstart used it)
    require(!allDocs.contains(Regex("rest:\\s*\\n\\s*control-plane-enabled"))) {
        "Docs must not contain nested rest: control-plane-enabled YAML form (use the correct flat property rest-control-plane-enabled)."
    }

    // Forbidden: "marked dead" — the worker marks continuations CANCELLED, not "dead"
    require(!jdbcRunbookText.contains("marked dead")) {
        "Runbook must not say 'marked dead'. Terminal failure marks the continuation CANCELLED."
    }

    // Forbidden: invented store name
    val inventedStore = Regex("SovereignOpsApprovedContinuationResumeStore")
    require(!allDocs.contains(inventedStore)) {
        "Docs must not reference invented store name SovereignOpsApprovedContinuationResumeStore. Use ApprovedContinuationResumeQueue or the real SPI name."
    }

    // Forbidden: invented queue statuses (check only CHANGELOG.md — the runbook
    // legitimately uses DEAD in the outbox dispatch model, which is a different domain)
    val inventedStatuses = listOf("QUEUED", "RUNNING", "RETRYING", "DEAD")
    inventedStatuses.forEach { status ->
        val pattern = Regex(status)
        require(!changelogText.contains(pattern)) {
            "CHANGELOG.md must not contain invented queue status '$status'. Use real status values like eligibleNow, delayedRetry, activeLeases, expiredLeases, terminalFailures."
        }
    }

    // Forbidden: wrong polling semantics
    val wrongPolling = Regex("status\\s*=\\s*'approved'")
    require(!jdbcRunbookText.contains(wrongPolling)) {
        "JDBC runbook must not contain 'status = \\'approved\\'' polling semantics. Use APPROVED + PENDING dual condition."
    }

    // Required: real SPI queue name
    require(changelogText.contains("ApprovedContinuationResumeQueue")) {
        "CHANGELOG.md must reference ApprovedContinuationResumeQueue (the real SPI name)."
    }

    // Required: correct flat property name
    require(changelogText.contains("rest-control-plane-enabled")) {
        "CHANGELOG.md must reference rest-control-plane-enabled (the correct flat property name)."
    }

    // ── PR #119: Approved-resume worker dashboards and alerts ──

    // File existence checks
    require(resumeAlerts.exists()) { "Prometheus alert file missing at ${resumeAlerts.absolutePath}" }
    require(resumeDashboard.exists()) { "Grafana dashboard file missing at ${resumeDashboard.absolutePath}" }
    require(resumeRunbook.exists()) { "Observability runbook missing at ${resumeRunbook.absolutePath}" }

    // Required phrases in alerts
    val alertText = resumeAlerts.readText()
    require(alertText.contains("TramAIApprovedResumeWorkerFailures")) {
        "Prometheus alerts must contain TramAIApprovedResumeWorkerFailures"
    }
    require(alertText.contains("TramAIApprovedResumeExpiredLeases")) {
        "Prometheus alerts must contain TramAIApprovedResumeExpiredLeases"
    }
    require(alertText.contains("TramAIApprovedResumeTerminalFailures")) {
        "Prometheus alerts must contain TramAIApprovedResumeTerminalFailures"
    }

    // Forbidden: no individual identifiers as labels in alerts or dashboard
    val dashboard = resumeDashboard.readText()
    val resumeRunbookText = resumeRunbook.readText()
    require(!alertText.contains("approval_id")) {
        "Prometheus alerts must not contain approval_id as a label"
    }
    require(!dashboard.contains("\"approval_id\"")) {
        "Grafana dashboard must not contain approval_id as a label"
    }
    require(!resumeRunbookText.contains("approval_id")) {
        "Observability runbook must not contain approval_id"
    }
    require(!alertText.contains("workflow_run_id")) {
        "Prometheus alerts must not contain workflow_run_id as a label"
    }
    require(!dashboard.contains("\"workflow_run_id\"")) {
        "Grafana dashboard must not contain workflow_run_id as a label"
    }
    require(!resumeRunbookText.contains("workflow_run_id")) {
        "Observability runbook must not contain workflow_run_id"
    }
    require(!alertText.contains("resume_token")) {
        "Prometheus alerts must not contain resume_token as a label"
    }
    require(!dashboard.contains("\"resume_token\"")) {
        "Grafana dashboard must not contain resume_token as a label"
    }
    require(!resumeRunbookText.contains("resume_token")) {
        "Observability runbook must not contain resume_token"
    }
    require(!alertText.contains("exception_message")) {
        "Prometheus alerts must not contain exception_message as a label"
    }
    require(!dashboard.contains("\"exception_message\"")) {
        "Grafana dashboard must not contain exception_message as a label"
    }
    require(!resumeRunbookText.contains("exception_message")) {
        "Observability runbook must not contain exception_message"
    }

    // Alert file must not claim production certification
    require(!alertText.contains("production certified")) {
        "Prometheus alerts must not claim production certification"
    }

    // STATUS.md must reference the new dashboard and alert examples
    require(statusText.contains("dashboard and alert examples")) {
        "STATUS.md must reference dashboard/alert examples"
    }

    // Forbidden: wrong config prefix for approved-resume worker metrics
    require(!resumeRunbookText.contains("tramai.sovereign.approved-resume.worker.metrics-enabled")) {
        "Runbook must not use stale prefix tramai.sovereign.approved-resume.worker.metrics-enabled; use tramai.sovereign.ops.actuator.approved-resume-worker-metrics.enabled"
    }
    require(!resumeRunbookText.contains("tramai.sovereign.approved-resume.queue.snapshot-refresh-interval")) {
        "Runbook must not use stale prefix tramai.sovereign.approved-resume.queue.snapshot-refresh-interval; use tramai.sovereign.ops.actuator.approved-resume-worker-metrics.queue-snapshot-refresh-interval"
    }

    // Required: correct config properties in runbook
    require(resumeRunbookText.contains("tramai.sovereign.ops.actuator.approved-resume-worker-metrics.enabled")) {
        "Runbook must reference the real config property tramai.sovereign.ops.actuator.approved-resume-worker-metrics.enabled"
    }
    require(resumeRunbookText.contains("tramai.sovereign.ops.actuator.approved-resume-worker-metrics.queue-snapshot-refresh-interval")) {
        "Runbook must reference the real config property tramai.sovereign.ops.actuator.approved-resume-worker-metrics.queue-snapshot-refresh-interval"
    }

    // Forbidden: histogram_quantile(0.95 in runbook — dashboard uses average, not p95
    require(!resumeRunbookText.contains("histogram_quantile(0.95")) {
        "Runbook must not recommend histogram_quantile(0.95) unless histogram buckets are explicitly documented/enabled for the cycle_duration_seconds timer."
    }

    // ── Golden path guide consistency ──

    require(goldenPathGuide.exists()) {
        "Missing approval gateway golden path guide at ${goldenPathGuide.absolutePath}"
    }

    val goldenPathText = goldenPathGuide.readText()

    // Forbidden: stale "Reviewer UI | Not implemented yet" limitation
    require(!goldenPathText.contains("Reviewer UI | Not implemented yet")) {
        "Approval gateway golden path guide must not say reviewer UI is not implemented; " +
            "preview reviewer UI exists and is disabled by default."
    }

    // Required: golden path mentions updated reviewer UI status
    require(goldenPathText.contains("Preview reviewer UI available, disabled by default")) {
        "Approval gateway golden path guide must document that preview reviewer UI is available " +
            "and disabled by default."
    }

    // Forbidden: stale Preview language after RC+ promotion
    require(!goldenPathText.contains("using the Preview `ApprovalGateway` API")) {
        "Approval gateway golden path guide must not describe ApprovalGateway as Preview after RC+ promotion."
    }

    require(!goldenPathText.contains("Preview `ApprovalRequestResult.toWorkflowResult")) {
        "Approval gateway golden path guide must not describe toWorkflowResult as Preview after RC+ promotion."
    }

    // Required: guide documents the RC+ Stable split correctly
    require(goldenPathText.contains("RC+ Stable golden path")) {
        "Approval gateway golden path guide must document the RC+ Stable golden path status."
    }

    require(goldenPathText.contains("REST control plane") && goldenPathText.contains("Preview")) {
        "Approval gateway golden path guide must keep operational REST/control-plane surfaces marked Preview."
    }

    // ── Golden path test must not reference low-level stores ──

    require(goldenPathTest.exists()) {
        "Missing approval gateway golden path test at ${goldenPathTest.absolutePath}"
    }

    val forbiddenStoreReferences = listOf(
        "ApprovalStore",
        "SuspendedInvocationStore",
        "ApprovalContinuationStore",
        "JdbcApprovalStore",
        "JdbcSuspendedInvocationStore",
        "JdbcApprovalContinuationStore",
        "SovereignOpsAuditOutboxStore",
        "SovereignOpsApprovalMutationStore",
        "SovereignOpsWorkerLeaseStore",
        "ApprovalResumeCredentialStore",
    )
    val testSource = goldenPathTest.readText()
    forbiddenStoreReferences.forEach { forbidden ->
        require(!testSource.contains(forbidden)) {
            "ApprovalGateway golden path test must not reference low-level store type: $forbidden"
        }
    }

    // ── Spring golden path smoke test: workflow class must not reference low-level stores ──

    require(springSmokeTest.exists()) {
        "Missing Spring approval gateway golden path smoke test at ${springSmokeTest.absolutePath}"
    }
    val smokeSource = springSmokeTest.readText()

    require(smokeSource.contains("TestApprovalGatewayRequestFactory")) {
        "Spring golden path smoke test must use the reusable TestApprovalGatewayRequestFactory fixture. " +
            "Found: ${smokeSource.lines().firstOrNull { it.contains("ApprovalGatewayRequestFactory") } ?: "no factory reference"}"
    }

    val workflowSection = smokeSource
        .substringAfter("class ExampleApprovalWorkflow")
        .substringBefore("class SmokeTestDataSourceConfig")

    forbiddenStoreReferences.forEach { forbidden ->
        require(!workflowSection.contains(forbidden)) {
            "ExampleApprovalWorkflow must not depend on low-level store: $forbidden"
        }
    }

    // ── Regulated claim triage approval gateway factory must use the builder ──

    require(regulatedFactoryFile.exists()) {
        "Missing regulated claim triage factory at ${regulatedFactoryFile.absolutePath}"
    }
    val regulatedFactorySource = regulatedFactoryFile.readText()

    require(regulatedFactorySource.contains("TestApprovalGatewayPersistenceRequestBuilder")) {
        "Regulated claim triage factory must use TestApprovalGatewayPersistenceRequestBuilder."
    }

    val forbiddenLowLevelConstruction = listOf(
        "ApprovalRequest(",
        "ApprovalContinuation(",
        "SuspendedInvocationMetadata(",
        "SensitiveReplayEnvelope.of(",
        "ReplayEnvelopeDigestHelper.compute(",
        "Sha256ToolArgumentsDigester()",
    )

    forbiddenLowLevelConstruction.forEach { forbidden ->
        require(!regulatedFactorySource.contains(forbidden)) {
            "Regulated claim triage factory must not manually construct low-level approval records: $forbidden"
        }
    }

    // ── Non-transactional gateway fallback requires explicit opt-in ──

    require(gatewayAutoConfig.exists()) {
        "Missing approval gateway auto-configuration."
    }
    val gatewayConfigSource = gatewayAutoConfig.readText()
    require(gatewayConfigSource.contains("non-transactional-fallback-enabled")) {
        "DefaultApprovalGateway fallback must require explicit non-transactional fallback opt-in."
    }
    require(gatewayConfigSource.contains("matchIfMissing = false")) {
        "Non-transactional DefaultApprovalGateway fallback must not be enabled by default."
    }
    require(!gatewayConfigSource.contains("DefaultApprovalGateway is created as fallback")) {
        "KDoc must no longer describe DefaultApprovalGateway as automatic fallback."
    }
    require(gatewayConfigSource.contains("only when")) {
        "KDoc must document that DefaultApprovalGateway requires explicit opt-in."
    }

    // ── Human approval ergonomics doc must not contain stale claims ──

    val humanApprovalErgonomicsText = humanApprovalErgonomics.readText()

    require(!humanApprovalErgonomicsText.contains(
        "Spring Auto-configuration creates DefaultApprovalGateway when the factory bean is present alongside the JDBC stores",
    )) {
        "Human approval ergonomics doc must not claim DefaultApprovalGateway is automatically created alongside JDBC stores."
    }

    require(humanApprovalErgonomicsText.contains("non-transactional-fallback-enabled=true")) {
        "Human approval ergonomics doc must document explicit opt-in for DefaultApprovalGateway."
    }

    require(humanApprovalErgonomicsText.contains("PR #130")) {
        "Human approval ergonomics doc must include the post-#130 fallback hardening update."
    }

    // ── CHANGELOG must not contain stale auto-wiring claims ──

    require(!changelogText.contains("Spring auto-configuration creates the DefaultApprovalGateway bean")) {
        "CHANGELOG must not claim DefaultApprovalGateway is automatically created by Spring auto-configuration."
    }

    // ── Java interop test for approval workflow mapper ──

    require(javaInteropTest.exists()) {
        "Missing Java interop test for ApprovalRequestResult workflow mapper at ${javaInteropTest.absolutePath}"
    }

    val javaInteropSource = javaInteropTest.readText()

    require(javaInteropSource.contains("fromApprovalRequestResult")) {
        "Java interop test must prove Java can call the approval workflow mapper (fromApprovalRequestResult)."
    }

    val javaInteropRequiredOutputs = listOf(
        "AlreadyApproved",
        "Suspended",
        "AlreadyDenied",
        "Expired",
    )

    javaInteropRequiredOutputs.forEach { outcome ->
        require(javaInteropSource.contains(outcome)) {
            "Java interop test must cover $outcome mapping."
        }
    }

    // Must use String-based factories, not inline-value-class-returning factories
    require(javaInteropSource.contains("ApprovalRequestResults.suspended(")) {
        "Java interop test must prove Java can construct Suspended via String-based factory."
    }

    require(javaInteropSource.contains("HumanApprovalDecisions.approved(")) {
        "Java interop test must prove Java can construct Approved via String-based factory."
    }

    // Prove @JvmOverloads short forms compile without comment parameter
    require(javaInteropSource.contains("usesShortApprovedOverloadWithoutComment")) {
        "Java interop test must prove @JvmOverloads works for approved() without comment."
    }

    require(javaInteropSource.contains("usesShortDeniedOverloadWithoutComment")) {
        "Java interop test must prove @JvmOverloads works for denied() without comment."
    }

    // Prove the decision-aware lambda contract: terminal states must not invoke lambda
    require(javaInteropSource.contains("should not run")) {
        "Java interop test must prove the decision-aware lambda contract (terminal states skip lambda)."
    }

    // Prove HumanApprovalDecision approvalId has a clean Java getter
    require(javaInteropSource.contains("decision.getApprovalId()")) {
        "Java interop test must prove HumanApprovalDecision approvalId has a clean Java getter."
    }
}
