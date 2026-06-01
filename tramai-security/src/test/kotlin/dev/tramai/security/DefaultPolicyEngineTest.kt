package dev.tramai.security

import dev.tramai.core.policy.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DefaultPolicyEngineTest {

    private val secureEngine = DefaultPolicyEngine(PolicyConfiguration.secure())
    private val previewEngine = DefaultPolicyEngine(PolicyConfiguration.preview())
    private val localSecureEngine = DefaultPolicyEngine(
        PolicyConfiguration.secure().copy(trustedLocalProviders = setOf("ollama"))
    )

    private fun ctx(
        enforcementPoint: EnforcementPoint,
        correlationId: String = "c1",
        toolName: String? = null,
        toolSecurity: ToolSecurityMetadata? = null,
        modelName: String? = null,
        providerId: String? = null,
        fallbackProviderId: String? = null,
        dataClassification: DataClassification? = null,
    ) = PolicyContext(
        enforcementPoint = enforcementPoint,
        correlationId = correlationId,
        actorId = "test",
        policyVersion = "1.0.0",
        toolName = toolName,
        toolSecurity = toolSecurity,
        modelName = modelName,
        providerId = providerId,
        fallbackProviderId = fallbackProviderId,
        dataClassification = dataClassification,
    )

    @Test
    fun `unknown tool is denied`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_TOOL_EXECUTION, toolName = "unknown-tool")
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("unknown-tool")
        }
    }

    @Test
    fun `unknown model is denied`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_PROVIDER_RESOLUTION, modelName = "unknown-model")
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("unknown-model")
        }
    }

    @Test
    fun `unknown provider fallback is denied`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_FALLBACK, fallbackProviderId = "unknown-fallback")
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
        }
    }

    @Test
    fun `HIGH risk tool requires approval`() {
        runBlocking {
            val config = PolicyConfiguration.secure().copy(
                allowedTools = setOf("payment-tool"),
                allowedPermissions = setOf("payment.execute"),
            )
            val engine = DefaultPolicyEngine(config)
            val decision = engine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_TOOL_EXECUTION,
                    toolName = "payment-tool",
                    toolSecurity = ToolSecurityMetadata(
                        permission = "payment.execute",
                        risk = RiskLevel.HIGH,
                        approval = ApprovalMode.HUMAN_REQUIRED,
                        managedNetworkEgress = ManagedNetworkEgress.DENY,
                        audit = AuditDetail.FULL,
                    ),
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.RequireApproval::class.java)
        }
    }

    @Test
    fun `LOW risk tool with allowed config is allowed`() {
        runBlocking {
            val config = PolicyConfiguration.secure().copy(
                allowedTools = setOf("safe-tool"),
                allowedPermissions = setOf("safe-tool"),
            )
            val engine = DefaultPolicyEngine(config)
            val decision = engine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_TOOL_EXECUTION,
                    toolName = "safe-tool",
                    toolSecurity = ToolSecurityMetadata(
                        permission = "safe-tool",
                        risk = RiskLevel.LOW,
                        approval = ApprovalMode.AUTO,
                        managedNetworkEgress = ManagedNetworkEgress.ALLOW,
                        audit = AuditDetail.MINIMAL,
                    ),
                )
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    @Test
    fun `LOW risk with HUMAN_REQUIRED approval requires approval`() {
        runBlocking {
            val config = PolicyConfiguration.secure().copy(
                allowedTools = setOf("review-tool"),
                allowedPermissions = setOf("review.execute"),
                requireApprovalForRiskLevel = setOf(RiskLevel.HIGH, RiskLevel.CRITICAL),
            )
            val engine = DefaultPolicyEngine(config)
            val decision = engine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_TOOL_EXECUTION,
                    toolName = "review-tool",
                    toolSecurity = ToolSecurityMetadata(
                        permission = "review.execute",
                        risk = RiskLevel.LOW,
                        approval = ApprovalMode.HUMAN_REQUIRED,
                        managedNetworkEgress = ManagedNetworkEgress.DENY,
                        audit = AuditDetail.FULL,
                    ),
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.RequireApproval::class.java)
        }
    }

    @Test
    fun `MEDIUM risk with HUMAN_REQUIRED_WITH_TIMEOUT approval requires approval`() {
        runBlocking {
            val config = PolicyConfiguration.secure().copy(
                allowedTools = setOf("staged-tool"),
                allowedPermissions = setOf("staged.execute"),
                requireApprovalForRiskLevel = setOf(RiskLevel.HIGH, RiskLevel.CRITICAL),
            )
            val engine = DefaultPolicyEngine(config)
            val decision = engine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_TOOL_EXECUTION,
                    toolName = "staged-tool",
                    toolSecurity = ToolSecurityMetadata(
                        permission = "staged.execute",
                        risk = RiskLevel.MEDIUM,
                        approval = ApprovalMode.HUMAN_REQUIRED_WITH_TIMEOUT,
                        managedNetworkEgress = ManagedNetworkEgress.DENY,
                        audit = AuditDetail.FULL,
                    ),
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.RequireApproval::class.java)
        }
    }

    @Test
    fun `LOW risk with AUTO approval is allowed`() {
        runBlocking {
            val config = PolicyConfiguration.secure().copy(
                allowedTools = setOf("auto-tool"),
                allowedPermissions = setOf("auto.execute"),
                requireApprovalForRiskLevel = setOf(RiskLevel.HIGH, RiskLevel.CRITICAL),
            )
            val engine = DefaultPolicyEngine(config)
            val decision = engine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_TOOL_EXECUTION,
                    toolName = "auto-tool",
                    toolSecurity = ToolSecurityMetadata(
                        permission = "auto.execute",
                        risk = RiskLevel.LOW,
                        approval = ApprovalMode.AUTO,
                        managedNetworkEgress = ManagedNetworkEgress.ALLOW,
                        audit = AuditDetail.MINIMAL,
                    ),
                )
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    @Test
    fun `HIGH risk with AUTO approval but HIGH globally requires approval`() {
        runBlocking {
            val config = PolicyConfiguration.secure().copy(
                allowedTools = setOf("high-auto-tool"),
                allowedPermissions = setOf("high-auto.execute"),
                requireApprovalForRiskLevel = setOf(RiskLevel.HIGH),
            )
            val engine = DefaultPolicyEngine(config)
            val decision = engine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_TOOL_EXECUTION,
                    toolName = "high-auto-tool",
                    toolSecurity = ToolSecurityMetadata(
                        permission = "high-auto.execute",
                        risk = RiskLevel.HIGH,
                        approval = ApprovalMode.AUTO,
                        managedNetworkEgress = ManagedNetworkEgress.DENY,
                        audit = AuditDetail.FULL,
                    ),
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.RequireApproval::class.java)
        }
    }

    @Test
    fun `response return without classification is allowed`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_RESPONSE_RETURN)
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    @Test
    fun `RESTRICTED data to non-local provider is denied`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_RESPONSE_RETURN,
                    providerId = "openai",
                    dataClassification = DataClassification.RESTRICTED,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("restricted-data-egress-blocked")
        }
    }

    @Test
    fun `BEFORE_WORKFLOW_RESUME is denied as unimplemented`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_WORKFLOW_RESUME)
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("workflow-resume-unimplemented")
        }
    }

    @Test
    fun `preview mode allows unknown tools`() {
        runBlocking {
            val decision = previewEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_TOOL_EXECUTION, toolName = "any-tool")
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    @Test
    fun `preview mode allows unknown models`() {
        runBlocking {
            val decision = previewEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_PROVIDER_RESOLUTION, modelName = "any-model")
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    @Test
    fun `preview mode requires approval for CRITICAL risk`() {
        runBlocking {
            val decision = previewEngine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_TOOL_EXECUTION,
                    toolName = "dangerous-tool",
                    toolSecurity = ToolSecurityMetadata(
                        permission = "dangerous.execute",
                        risk = RiskLevel.CRITICAL,
                        approval = ApprovalMode.HUMAN_REQUIRED,
                        managedNetworkEgress = ManagedNetworkEgress.DENY,
                        audit = AuditDetail.FULL,
                    ),
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.RequireApproval::class.java)
        }
    }

    @Test
    fun `provider invocation after resolution is allowed`() {
        runBlocking {
            val engine = DefaultPolicyEngine(PolicyConfiguration.secure().copy(allowedProviders = setOf("openai")))
            val decision = engine.evaluate(
                ctx(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, providerId = "openai", modelName = "gpt-4")
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    @Test
    fun `classified provider invocation without provider id is denied`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    dataClassification = DataClassification.INTERNAL,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("classification-provider-missing")
        }
    }

    @Test
    fun `RESTRICTED provider invocation to non-local provider is denied before allowlist`() {
        runBlocking {
            val engine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedProviders = setOf("openai"),
                )
            )
            val decision = engine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = "openai",
                    dataClassification = DataClassification.RESTRICTED,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("restricted-data-egress-blocked")
        }
    }

    @Test
    fun `response return for classified request without provider id is denied`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_RESPONSE_RETURN,
                    dataClassification = DataClassification.INTERNAL,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("classification-provider-missing")
        }
    }

    @Test
    fun `tool result reinjection is allowed`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION, toolName = "echo")
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    @Test
    fun `fallback without a provider is denied`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_FALLBACK)
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("no-fallback-provider")
        }
    }

    @Test
    fun `unknown provider is denied at invocation`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, providerId = "unknown-provider")
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("unknown-provider")
        }
    }

    @Test
    fun `INTERNAL data is allowed for local provider`() {
        runBlocking {
            val decision = localSecureEngine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_RESPONSE_RETURN,
                    providerId = "ollama",
                    dataClassification = DataClassification.INTERNAL,
                )
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    @Test
    fun `HIGH risk tool exposure is allowed when tool metadata and permission are valid`() {
        runBlocking {
            val config = PolicyConfiguration.secure().copy(
                allowedTools = setOf("high-risk-tool"),
                allowedPermissions = setOf("high-risk.execute"),
            )
            val engine = DefaultPolicyEngine(config)
            val decision = engine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_TOOL_EXPOSURE,
                    toolName = "high-risk-tool",
                    toolSecurity = ToolSecurityMetadata(
                        permission = "high-risk.execute",
                        risk = RiskLevel.HIGH,
                        approval = ApprovalMode.HUMAN_REQUIRED,
                        managedNetworkEgress = ManagedNetworkEgress.DENY,
                        audit = AuditDetail.FULL,
                    ),
                )
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    @Test
    fun `tool with unlisted permission is denied`() {
        runBlocking {
            val config = PolicyConfiguration.secure().copy(
                allowedTools = setOf("payment-tool"),
                // allowedPermissions is empty, so "execute.payments" is not allowed
            )
            val engine = DefaultPolicyEngine(config)
            val decision = engine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_TOOL_EXECUTION,
                    toolName = "payment-tool",
                    toolSecurity = ToolSecurityMetadata(
                        permission = "execute.payments",
                        risk = RiskLevel.LOW,
                        approval = ApprovalMode.AUTO,
                        managedNetworkEgress = ManagedNetworkEgress.DENY,
                        audit = AuditDetail.MINIMAL,
                    ),
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("tool-permission-denied")
        }
    }

    @Test
    fun `secure mode denies null toolSecurity metadata`() {
        runBlocking {
            val engine = DefaultPolicyEngine(PolicyConfiguration.secure().copy(allowedTools = setOf("legacy-tool")))
            val decision = engine.evaluate(
                ctx(EnforcementPoint.BEFORE_TOOL_EXECUTION, toolName = "legacy-tool", toolSecurity = null)
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("tool-metadata-missing")
        }
    }

    @Test
    fun `secure mode denies null toolSecurity metadata during exposure`() {
        runBlocking {
            val engine = DefaultPolicyEngine(PolicyConfiguration.secure().copy(allowedTools = setOf("legacy-tool")))
            val decision = engine.evaluate(
                ctx(EnforcementPoint.BEFORE_TOOL_EXPOSURE, toolName = "legacy-tool", toolSecurity = null)
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("tool-metadata-missing")
        }
    }

    @Test
    fun `secure mode denies LEGACY_PERMISSIVE compatibility mode`() {
        runBlocking {
            val engine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedTools = setOf("legacy-tool"),
                    allowedPermissions = setOf("legacy.unrestricted"),
                )
            )
            val decision = engine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_TOOL_EXECUTION,
                    toolName = "legacy-tool",
                    toolSecurity = ToolSecurityMetadata.legacyPermissive(),
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("tool-metadata-legacy-permissive")
        }
    }

    @Test
    fun `secure mode denies LEGACY_PERMISSIVE compatibility mode during exposure`() {
        runBlocking {
            val engine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    allowedTools = setOf("legacy-tool"),
                    allowedPermissions = setOf("legacy.unrestricted"),
                )
            )
            val decision = engine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_TOOL_EXPOSURE,
                    toolName = "legacy-tool",
                    toolSecurity = ToolSecurityMetadata.legacyPermissive(),
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("tool-metadata-legacy-permissive")
        }
    }

    @Test
    fun `preview mode allows null toolSecurity metadata`() {
        runBlocking {
            val decision = previewEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_TOOL_EXECUTION, toolName = "legacy-tool", toolSecurity = null)
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    @Test
    fun `preview mode allows null toolSecurity metadata during exposure`() {
        runBlocking {
            val decision = previewEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_TOOL_EXPOSURE, toolName = "legacy-tool", toolSecurity = null)
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    @Test
    fun `HIGH risk tool with missing permission is denied not approved`() {
        runBlocking {
            val engine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(allowedTools = setOf("payment-tool"))
            )
            val decision = engine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_TOOL_EXECUTION,
                    toolName = "payment-tool",
                    toolSecurity = ToolSecurityMetadata(
                        permission = "payment.execute",
                        risk = RiskLevel.HIGH,
                        approval = ApprovalMode.HUMAN_REQUIRED,
                        managedNetworkEgress = ManagedNetworkEgress.DENY,
                        audit = AuditDetail.FULL,
                    ),
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("tool-permission-denied")
        }
    }

    @Test
    fun `RESTRICTED data with null provider is denied`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_RESPONSE_RETURN,
                    providerId = null,
                    dataClassification = DataClassification.RESTRICTED,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("classification-provider-missing")
        }
    }

    @Test
    fun `INTERNAL data with null provider is denied`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_RESPONSE_RETURN,
                    providerId = null,
                    dataClassification = DataClassification.INTERNAL,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("classification-provider-missing")
        }
    }

    @Test
    fun `RESTRICTED data with untrusted provider is denied`() {
        runBlocking {
            val engine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(trustedLocalProviders = setOf("local"))
            )
            val decision = engine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_RESPONSE_RETURN,
                    providerId = "ollama",
                    dataClassification = DataClassification.RESTRICTED,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("restricted-data-egress-blocked")
        }
    }

    @Test
    fun `RESTRICTED data with trusted local provider is allowed`() {
        runBlocking {
            val decision = localSecureEngine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_RESPONSE_RETURN,
                    providerId = "ollama",
                    dataClassification = DataClassification.RESTRICTED,
                )
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    @Test
    fun `provider invocation with null providerId is denied`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, providerId = null, modelName = "gpt-4")
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("provider-id-missing")
        }
    }

    @Test
    fun `provider resolution with null modelName is denied`() {
        runBlocking {
            val decision = secureEngine.evaluate(
                ctx(EnforcementPoint.BEFORE_PROVIDER_RESOLUTION, modelName = null)
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("model-name-missing")
        }
    }
}
