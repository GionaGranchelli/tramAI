package dev.tramai.examples.springaiboundary

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.InMemoryAuditStore
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager

/**
 * Executable demonstration of the declared boundary: the same wiring the proofs use, run end to end so
 * `./gradlew :examples:spring-ai-authority-boundary:run` shows the ALLOW and DENY outcomes rather than
 * only asserting them.
 *
 * The model is a deterministic local provider — it proposes the action and nothing else. The enforcement
 * boundary is the real one, evaluated against the real deny-by-default engine.
 */
object SpringAiBoundaryMain {
    private const val RUN_ID = "run-xr1-demo"
    private const val CORRELATION_ID = "corr-xr1-demo"
    private const val POLICY_VERSION = "policy-xr1-v1"
    private const val TOOL_PERMISSION = "ledger.append"

    @JvmStatic
    fun main(args: Array<String>) {
        println("XR1-0001 — TramAI authority over a Spring AI tool boundary")
        println("Enforcement point: $BOUNDARY_ENFORCEMENT_POINT")

        demonstrate(
            label = "ALLOW (the tool and its permission are admissible for this policy)",
            policy =
                PolicyConfiguration(
                    allowedTools = setOf(SideEffectfulToolCallback.TOOL_NAME),
                    allowedPermissions = setOf(TOOL_PERMISSION),
                ),
        )
        demonstrate(
            label = "DENY (deny-by-default: neither the tool nor its permission is admissible)",
            policy = PolicyConfiguration(),
        )
    }

    private fun demonstrate(
        label: String,
        policy: PolicyConfiguration,
    ) {
        val tool = SideEffectfulToolCallback()
        val context = context()
        val auditEngine = AuditEngine(InMemoryAuditStore())
        val boundary =
            GovernedToolCallback(
                delegate = tool,
                engine = DefaultPolicyEngine(policy),
                auditEngine = auditEngine,
                context = context,
            )
        val model = StubToolRequestingChatModel(SideEffectfulToolCallback.TOOL_NAME, """{"value":"from-the-model"}""")
        val options =
            ToolCallingChatOptions
                .builder()
                .toolCallbacks(boundary)
                .internalToolExecutionEnabled(true)
                .build()
        val prompt = Prompt(UserMessage("append a record to the ledger"), options)

        println()
        println("── $label")
        val execution = ToolCallingManager.builder().build().executeToolCalls(prompt, model.call(prompt))
        val recorded = boundary.events().single()

        println("   model proposed: ${SideEffectfulToolCallback.TOOL_NAME}")
        println("   TramAI decided: ${recorded.decision} (${recorded.reasonCode ?: "no reason code"})")
        println("   action ledger:  ${tool.invocations().size} invocation(s) — the observable side effect")
        execution
            .conversationHistory()
            .filterIsInstance<ToolResponseMessage>()
            .flatMap { it.responses }
            .forEach { response -> println("   framework recorded as the tool result: ${response.responseData()}") }
        println("   evidence: ${boundary.evidence().size} record(s) through the tool.permission exporter")
    }

    private fun context(): BoundaryContext =
        BoundaryContext(
            identity =
                GovernedRunIdentity(
                    deployment =
                        WorkloadDeploymentIdentity(
                            workloadId = WorkloadId("workload-xr1"),
                            configuration =
                                WorkloadConfigurationIdentity(
                                    id = ConfigurationId("configuration-xr1"),
                                    version = ConfigurationVersion("v1"),
                                ),
                            environmentId = EnvironmentId("environment-local"),
                            deploymentId = DeploymentId("deployment-xr1-1"),
                        ),
                    runId = RunId(RUN_ID),
                ),
            security =
                ToolSecurityMetadata(
                    permission = "ledger.append",
                    risk = RiskLevel.LOW,
                    approval = ApprovalMode.AUTO,
                    managedNetworkEgress = ManagedNetworkEgress.ALLOW,
                    audit = AuditDetail.DECISION_ONLY,
                ),
            policyVersion = POLICY_VERSION,
            actorId = "spring-ai-workload",
            correlationId = CORRELATION_ID,
        )
}
