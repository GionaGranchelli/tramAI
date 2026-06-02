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

    // ═══════════════════════════════════════════════════════════════════════
    // Epic 2.3 / 2.4 — Classification-aware provider routing matrix tests
    // ═══════════════════════════════════════════════════════════════════════

    private val routingEngine = DefaultPolicyEngine(
        PolicyConfiguration.secure().copy(
            allowedProviders = setOf("*"),
            allowedFallbackProviders = setOf("*"),
            allowedModels = setOf("*"),
            providerRouting = ProviderRoutingConfiguration(
                providerZones = mapOf(
                    "ollama" to ProviderTrustZone.LOCAL,
                    "eu-openai" to ProviderTrustZone.EU_CLOUD,
                    "openai" to ProviderTrustZone.GLOBAL_CLOUD,
                ),
                enabled = true,
            ),
        )
    )

    private fun routingCtx(
        enforcementPoint: EnforcementPoint,
        providerId: String? = null,
        fallbackProviderId: String? = null,
        dataClassification: DataClassification? = null,
    ) = PolicyContext(
        enforcementPoint = enforcementPoint,
        correlationId = "routing-test",
        actorId = "test",
        policyVersion = "1.0.0",
        providerId = providerId,
        fallbackProviderId = fallbackProviderId,
        dataClassification = dataClassification,
    )

    // 1. RESTRICTED → local allowed
    @Test
    fun `RESTRICTED classification with LOCAL zone is allowed for primary invocation`() {
        runBlocking {
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = "ollama",
                    dataClassification = DataClassification.RESTRICTED,
                )
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    // 2. RESTRICTED → EU denied
    @Test
    fun `RESTRICTED classification with EU_CLOUD zone is denied for primary invocation`() {
        runBlocking {
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = "eu-openai",
                    dataClassification = DataClassification.RESTRICTED,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("classification-routing-blocked")
        }
    }

    // 3. RESTRICTED → global denied
    @Test
    fun `RESTRICTED classification with GLOBAL_CLOUD zone is denied for primary invocation`() {
        runBlocking {
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = "openai",
                    dataClassification = DataClassification.RESTRICTED,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("classification-routing-blocked")
        }
    }

    // 4. CONFIDENTIAL → local allowed
    @Test
    fun `CONFIDENTIAL classification with LOCAL zone is allowed for primary invocation`() {
        runBlocking {
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = "ollama",
                    dataClassification = DataClassification.CONFIDENTIAL,
                )
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    // 5. CONFIDENTIAL → EU allowed
    @Test
    fun `CONFIDENTIAL classification with EU_CLOUD zone is allowed for primary invocation`() {
        runBlocking {
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = "eu-openai",
                    dataClassification = DataClassification.CONFIDENTIAL,
                )
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    // 6. CONFIDENTIAL → global denied
    @Test
    fun `CONFIDENTIAL classification with GLOBAL_CLOUD zone is denied for primary invocation`() {
        runBlocking {
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = "openai",
                    dataClassification = DataClassification.CONFIDENTIAL,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("classification-routing-blocked")
        }
    }

    // 7. INTERNAL → global allowed
    @Test
    fun `INTERNAL classification with GLOBAL_CLOUD zone is allowed for primary invocation`() {
        runBlocking {
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = "openai",
                    dataClassification = DataClassification.INTERNAL,
                )
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    // 8. PUBLIC → global allowed
    @Test
    fun `PUBLIC classification with GLOBAL_CLOUD zone is allowed for primary invocation`() {
        runBlocking {
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = "openai",
                    dataClassification = DataClassification.PUBLIC,
                )
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    // 9. Unknown provider denied independently of zone
    @Test
    fun `unknown provider with routing enabled and INTERNAL data is denied due to missing zone`() {
        runBlocking {
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = "unknown-provider",
                    dataClassification = DataClassification.INTERNAL,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("provider-zone-missing")
        }
    }

    // 10. Missing provider zone denied
    @Test
    fun `provider without zone mapping is denied for classified request`() {
        runBlocking {
            val engine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    providerRouting = ProviderRoutingConfiguration(
                        providerZones = emptyMap(), // no zones configured
                        enabled = true,
                    ),
                )
            )
            val decision = engine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = "ollama",
                    dataClassification = DataClassification.PUBLIC,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("provider-zone-missing")
        }
    }

    // 11. Missing routing rule denied
    @Test
    fun `classification without routing rule is denied`() {
        runBlocking {
            val engine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    providerRouting = ProviderRoutingConfiguration(
                        providerZones = mapOf("ollama" to ProviderTrustZone.LOCAL),
                        rules = emptyMap(), // no rules defined
                        enabled = true,
                    ),
                )
            )
            val decision = engine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = "ollama",
                    dataClassification = DataClassification.PUBLIC,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("classification-routing-rule-missing")
        }
    }

    // 12. RESTRICTED local failure → global fallback denied (assert fallback call count == 0)
    @Test
    fun `RESTRICTED classification global fallback is denied at BEFORE_FALLBACK`() {
        runBlocking {
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_FALLBACK,
                    providerId = "ollama",
                    fallbackProviderId = "openai",
                    dataClassification = DataClassification.RESTRICTED,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("classification-fallback-blocked")
        }
    }

    // 13. CONFIDENTIAL local failure → EU fallback allowed
    @Test
    fun `CONFIDENTIAL classification EU_CLOUD fallback is allowed at BEFORE_FALLBACK`() {
        runBlocking {
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_FALLBACK,
                    providerId = "ollama",
                    fallbackProviderId = "eu-openai",
                    dataClassification = DataClassification.CONFIDENTIAL,
                )
            )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    // 14. Secure cached result reauthorization honors current zone policy
    @Test
    fun `cached result reauthorization honors current zone policy at response return`() {
        runBlocking {
            // When routing is enabled, response return re-checks the matrix
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_RESPONSE_RETURN,
                    providerId = "openai",
                    dataClassification = DataClassification.CONFIDENTIAL,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("classification-routing-blocked")
        }
    }

    // 15. Cache reuse denied after provider zone changes
    @Test
    fun `cache reuse is denied when provider zone changes to restricted zone`() {
        runBlocking {
            // Simulate reauthorization against current policy: provider
            // was previously LOCAL but zone config now maps it to GLOBAL_CLOUD
            val engine = DefaultPolicyEngine(
                PolicyConfiguration.secure().copy(
                    providerRouting = ProviderRoutingConfiguration(
                        providerZones = mapOf("ollama" to ProviderTrustZone.GLOBAL_CLOUD),
                        enabled = true,
                    ),
                )
            )
            val decision = engine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_RESPONSE_RETURN,
                    providerId = "ollama",
                    dataClassification = DataClassification.RESTRICTED,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("classification-routing-blocked")
        }
    }

    // 16. Preview backward compatibility works
    @Test
    fun `preview mode preserves legacy routing behavior when matrix is disabled`() {
        runBlocking {
            // Preview has trustedLocalProviders but providerRouting.enabled=false
            val decision = previewEngine.evaluate(
                ctx(
                    EnforcementPoint.BEFORE_RESPONSE_RETURN,
                    providerId = "ollama",
                    dataClassification = DataClassification.RESTRICTED,
                )
            )
            // With matrix disabled, legacy egress logic uses trustedLocalProviders
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }

    @Test
    fun `preview mode RESTRICTED data to non-local provider is still denied with legacy logic`() {
        runBlocking {
            val decision = previewEngine.evaluate(
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
    fun `classified request with null providerId returns classification-provider-missing when routing enabled`() {
        runBlocking {
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = null,
                    dataClassification = DataClassification.INTERNAL,
                )
            )
            assertThat(decision).isInstanceOf(PolicyDecision.Deny::class.java)
            assertThat((decision as PolicyDecision.Deny).reasonCode).isEqualTo("classification-provider-missing")
        }
    }

    @Test
    fun `unclassified request passes through routing matrix without denial`() {
        runBlocking {
            val decision = routingEngine.evaluate(
                routingCtx(
                    EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    providerId = "openai",
                    dataClassification = null,
                )
            )
            // No classification → routing matrix returns null → falls through to
            // allowlist check. Since routingEngine has wildcard allowedProviders,
            // it gets all the way to Allow.
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }
    }
}
