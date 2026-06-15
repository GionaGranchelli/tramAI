package dev.tramai.examples.sovereign

import dev.tramai.sovereign.SovereignDeploymentMode
import dev.tramai.sovereign.evidence.SovereignEvidencePackWriter
import dev.tramai.sovereign.evidence.ZeroEgressEvidenceV1
import java.nio.file.Path

fun main(args: Array<String>) {
    val outputDir = Path.of(ExampleOutputDirectory)
    val releaseBundle = resolveReleaseBundleEvidence(args)

    buildExampleHarness().use { harness ->
        val outcome = runApprovedWorkflow(
            runtime = harness.runtime,
            approvalStore = harness.approvalStore,
            auditStore = harness.auditStore,
        )

        val resultPath = outputDir.resolve("result.json")
        val auditPath = outputDir.resolve("audit-chain.json")
        val approvalPath = outputDir.resolve("approval-events.json")
        val evidencePath = outputDir.resolve("sovereign-evidence-pack-v1.json")

        writeInvoiceAssessment(resultPath, outcome.assessment)
        writeAuditEvents(auditPath, outcome.auditEvents)
        writeAuditEvents(approvalPath, approvalEvents(outcome.auditEvents))

        val evidencePack = harness.tramai.evidencePack(
            zeroEgress = ZeroEgressEvidenceV1(
                deploymentMode = SovereignDeploymentMode.STANDARD.name,
                runtimeBuildSucceeded = true,
                loopbackProviderInvocationSucceeded = true,
                loopbackProviderInvocationCount = harness.provider.capturedRequests.size,
                externalTcpProbeBlocked = false,
                externalDnsProbeBlocked = false,
            ),
            auditChain = auditChainEvidence(outcome.auditEvents),
            releaseBundle = releaseBundle,
        )
        SovereignEvidencePackWriter.write(evidencePack, evidencePath)

        println("Final assessment:")
        println(outcome.assessment.toString())
        println("Artifacts written to ${outputDir.toAbsolutePath().normalize()}")
    }
}
