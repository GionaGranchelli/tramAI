package dev.tramai.examples.sovereign

import dev.tramai.core.approval.ApprovalIdGenerator
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalTokenGenerator
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.model.RegisteredModel
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.approval.DefaultApprovalGateCoordinator
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalStore
import dev.tramai.security.approval.Sha256ApprovalTokenDigester
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import dev.tramai.security.audit.AuditChainVerifier
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.audit.toCanonicalJson
import dev.tramai.security.model.InMemoryModelRegistry
import dev.tramai.sovereign.SovereignProfileConfiguration
import dev.tramai.sovereign.SovereignTramai
import dev.tramai.sovereign.SovereignTramaiRuntime
import dev.tramai.sovereign.evidence.AuditChainEvidenceV1
import dev.tramai.sovereign.evidence.ReleaseBundleEvidenceLoader
import dev.tramai.sovereign.evidence.ReleaseBundleEvidenceV1
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking

internal const val ExampleOutputDirectory = "build/sovereign-document-intelligence"

internal val ExampleFixedClock: Clock = Clock.fixed(
    Instant.parse("2026-06-11T12:00:00Z"),
    ZoneId.of("UTC"),
)

internal fun exampleProfile(): SovereignProfileConfiguration = SovereignProfileConfiguration(
    allowedModels = setOf("local-invoice-model"),
    allowedProviders = setOf("local-provider"),
    allowedTools = setOf("schedule-payment"),
    allowedPermissions = setOf("payment.schedule"),
    providerZones = mapOf("local-provider" to ProviderTrustZone.LOCAL),
)

internal fun exampleModelRegistry() = InMemoryModelRegistry.builder()
    .register(
        RegisteredModel(
            registryEntryId = "invoice-model-local-v1",
            providerId = "local-provider",
            modelName = "local-invoice-model",
            revision = "1.0",
        ),
    )
    .build()

internal class SovereignDocumentIntelligenceHarness(
    val tramai: SovereignTramai,
    val runtime: SovereignTramaiRuntime,
    val provider: DeterministicInvoiceProvider,
    val ledger: InMemoryPaymentLedger,
    val approvalStore: InMemoryApprovalStore,
    val continuationStore: InMemoryApprovalContinuationStore,
    val auditStore: InMemoryAuditStore,
) : AutoCloseable {

    override fun close() {
        runtime.close()
    }
}

internal fun buildExampleHarness(
    clock: Clock = ExampleFixedClock,
    provider: DeterministicInvoiceProvider = DeterministicInvoiceProvider(),
    ledger: InMemoryPaymentLedger = InMemoryPaymentLedger(),
): SovereignDocumentIntelligenceHarness {
    val approvalStore = InMemoryApprovalStore(clock = clock)
    val continuationStore = InMemoryApprovalContinuationStore(clock = clock)
    val auditStore = InMemoryAuditStore()
    val toolArgumentsDigester: ToolArgumentsDigester = Sha256ToolArgumentsDigester()
    val approvalTokenDigester = Sha256ApprovalTokenDigester()
    val gateCoordinator = DefaultApprovalGateCoordinator(
        store = approvalStore,
        approvalIdGenerator = ApprovalIdGenerator { "approval-invoice-001" },
        approvalTokenGenerator = ApprovalTokenGenerator {
            ApprovalToken.parsePresented("approval-token-invoice-001")
        },
        approvalTokenDigester = approvalTokenDigester,
        clock = clock,
    )

    val tramai = SovereignTramai.builder()
        .profile(exampleProfile())
        .modelRegistry(exampleModelRegistry())
        .auditStore(auditStore)
        .provider(provider, name = "local-provider", default = true)
        .model("local-invoice-model", "local-provider")
        .tools(SchedulePaymentTool(ledger))
        .approvalContinuationStore(continuationStore)
        .toolArgumentsDigester(toolArgumentsDigester)
        .approvalGateCoordinator(gateCoordinator)
        .clock(clock)
        .build()

    return SovereignDocumentIntelligenceHarness(
        tramai = tramai,
        runtime = tramai.runtime(),
        provider = provider,
        ledger = ledger,
        approvalStore = approvalStore,
        continuationStore = continuationStore,
        auditStore = auditStore,
    )
}

internal data class WorkflowOutcome(
    val assessment: InvoiceAssessment,
    val suspension: ApprovalSuspendedException,
    val auditEvents: List<AuditEvent>,
)

internal fun runApprovedWorkflow(
    runtime: SovereignTramaiRuntime,
    approvalStore: InMemoryApprovalStore,
    auditStore: InMemoryAuditStore,
): WorkflowOutcome {
    val service = runtime.create(InvoiceAnalysisService::class)
    val suspension = try {
        runBlocking { service.analyze(classifiedInvoice()) }
        error("Expected approval suspension")
    } catch (e: ApprovalSuspendedException) {
        e
    }

    val stored = runBlocking { approvalStore.get(suspension.approvalId) }
        ?: error("Missing approval ${suspension.approvalId}")
    val approved = runBlocking {
        approvalStore.transition(
            suspension.approvalId,
            stored.version,
            dev.tramai.core.approval.ApprovalTransition.Approve(
                decidedBy = "demo-operator",
                comment = "Auto-approved for example workflow",
            ),
        )
    }

    val command = ResumeApprovalCommand(
        approvalId = suspension.approvalId,
        approvalExpectedVersion = approved.version,
        continuationExpectedVersion = suspension.continuationVersion,
        presentedToken = suspension.challenge.token,
        resumedBy = "demo-operator",
    )

    val assessment = runBlocking {
        runtime.resumeApprovalTyped<InvoiceAssessment>(command)
    }
    val auditEvents = runBlocking { auditStore.readStream(suspension.workflowRunId) }

    return WorkflowOutcome(
        assessment = assessment,
        suspension = suspension,
        auditEvents = auditEvents,
    )
}

internal fun approvalEvents(events: List<AuditEvent>): List<AuditEvent> =
    events.filter { it.enforcementPoint.startsWith("APPROVAL_") }

internal fun auditChainEvidence(events: List<AuditEvent>): AuditChainEvidenceV1 {
    val result = AuditChainVerifier.verify(events)
    return AuditChainEvidenceV1(
        isValid = result.isValid,
        totalEvents = events.size,
    )
}

internal fun resolveReleaseBundleEvidence(args: Array<String>): ReleaseBundleEvidenceV1? {
    val explicitManifest = args.firstOrNull { it.startsWith("--release-bundle-manifest=") }
        ?.substringAfter("--release-bundle-manifest=")
    val manifestPath = when {
        explicitManifest != null ->
            resolvePathFromCurrentOrAncestor(Path.of(explicitManifest)) ?: Path.of(explicitManifest)
        resolvePathFromCurrentOrAncestor(Path.of("build/sovereign-release/release-artifacts-v1.json")) != null ->
            resolvePathFromCurrentOrAncestor(Path.of("build/sovereign-release/release-artifacts-v1.json"))
        else -> null
    }
    return manifestPath?.let { ReleaseBundleEvidenceLoader.load(it) }
}

private fun resolvePathFromCurrentOrAncestor(candidate: Path): Path? {
    if (candidate.isAbsolute) {
        return candidate.takeIf(Files::exists)
    }
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
        val resolved = current.resolve(candidate).normalize()
        if (Files.exists(resolved)) {
            return resolved
        }
        current = current.parent
    }
    return null
}

internal fun writeInvoiceAssessment(path: Path, assessment: InvoiceAssessment) {
    path.parent?.let(Files::createDirectories)
    Files.writeString(path, assessment.toJson())
}

internal fun writeAuditEvents(path: Path, events: List<AuditEvent>) {
    path.parent?.let(Files::createDirectories)
    val json = buildString {
        appendLine("[")
        events.forEachIndexed { index, event ->
            append(event.toCanonicalJson().prependIndent("  "))
            if (index != events.lastIndex) {
                append(",")
            }
            appendLine()
        }
        appendLine("]")
    }
    Files.writeString(path, json)
}

private fun InvoiceAssessment.toJson(): String = """
    {
      "invoiceId": ${json(invoiceId)},
      "supplierName": ${json(supplierName)},
      "amountCents": $amountCents,
      "currency": ${json(currency)},
      "risk": ${json(risk.name)},
      "recommendedAction": ${json(recommendedAction.name)},
      "rationale": ${json(rationale)}
    }
""".trimIndent() + "\n"

private fun json(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (ch.code < 0x20) {
                    append("\\u%04x".format(ch.code))
                } else {
                    append(ch)
                }
            }
        }
    }
    append('"')
}
