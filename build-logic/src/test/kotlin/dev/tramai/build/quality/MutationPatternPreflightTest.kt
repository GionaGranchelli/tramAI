package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Preflight test that verifies mutation target class patterns in test-quality.yml
 * are behavior-specific and actually match existing classes.
 *
 * This test loads the YAML configuration directly (no Gradle build needed),
 * checks each pattern against known classes, and ensures no two families
 * have identical target sets.
 */
class MutationPatternPreflightTest {

    /** Known modules from ModuleCatalog. */
    private val knownModules: Set<String> = setOf(
        ":tramai-core",
        ":tramai-engine",
        ":tramai-security",
        ":tramai-sovereign",
        ":tramai-standalone",
        ":tramai-structured",
        ":tramai-orchestration",
        ":tramai-persistence-file",
        ":tramai-persistence-jdbc",
        ":tramai-anthropic",
        ":tramai-azure-openai",
        ":tramai-bedrock",
        ":tramai-bom",
        ":tramai-dashboard",
        ":tramai-deepseek",
        ":tramai-embedding",
        ":tramai-gemini",
        ":tramai-mcp",
        ":tramai-memory",
        ":tramai-memory-store",
        ":tramai-observability",
        ":tramai-ollama",
        ":tramai-openai",
        ":tramai-platform",
        ":tramai-rag",
        ":tramai-scheduler",
        ":tramai-server",
        ":tramai-spring",
        ":tramai-spring-boot-starter-local-provider-openai",
        ":tramai-spring-boot-starter-sovereign",
        ":tramai-spring-boot-starter-sovereign-ops",
        ":tramai-spring-boot-starter-sovereign-ops-actuator",
        ":tramai-spring-boot-starter-sovereign-ops-micrometer",
        ":tramai-spring-boot-starter-sovereign-ops-observability",
        ":tramai-spring-boot-starter-sovereign-ops-rest",
        ":tramai-spring-boot-starter-sovereign-persistence-file",
        ":tramai-spring-boot-starter-sovereign-persistence-jdbc",
        ":tramai-testing",
        ":tramai-vectorstore-chroma",
        ":tramai-vectorstore-pgvector",
        ":tramai-vectorstore-spi"
    )

    /**
     * Known fully-qualified class names grouped by module.
     * Kept in sync with the actual source files so pattern validation
     * is meaningful.
     */
    private val knownClasses: Map<String, Set<String>> = mapOf(
        "tramai-core" to setOf(
            "dev.tramai.core.provider.ProviderRegistry",
            "dev.tramai.core.provider.ModelProvider",
            "dev.tramai.core.provider.ProviderFailuresKt"
        ),
        "tramai-engine" to setOf(
            "dev.tramai.engine.TramaiEngine",
            "dev.tramai.engine.ModelRegistryEnforcer",
            "dev.tramai.engine.ToolRegistry",
            "dev.tramai.engine.RetryPolicySettings",
            "dev.tramai.engine.CircuitBreakerSettings",
            "dev.tramai.engine.ProviderCircuitBreaker",
            "dev.tramai.engine.ProviderRetryDelayPolicy",
            "dev.tramai.engine.LegacyPermissivePolicyEngine",
            "dev.tramai.engine.PolicyEnforcementHelper",
            "dev.tramai.engine.ToolResultFilteringSettings",
            "dev.tramai.engine.CanonicalMessageEncoder",
            "dev.tramai.engine.TokenBudgetSettings",
            "dev.tramai.engine.EngineExecutionIdentity",
            "dev.tramai.engine.ExecutionSecurityContext",
            "dev.tramai.engine.SensitiveReplayEnvelope",
            "dev.tramai.engine.ReplayEnvelopeFactory",
            "dev.tramai.engine.ReplayEnvelopeDigestHelper",
            "dev.tramai.engine.WorkflowDigestHelper",
            "dev.tramai.engine.ResumeOperationRegistry",
            "dev.tramai.engine.ResumeOperationReference",
            "dev.tramai.engine.ResumeDefinitionDigestHelper",
            "dev.tramai.engine.SuspendedInvocationStore",
            "dev.tramai.engine.InMemorySuspendedInvocationStore",
            "dev.tramai.engine.OperationResponseCache",
            "dev.tramai.engine.InMemoryOperationResponseCache",
            "dev.tramai.engine.EngineEventObserver",
            "dev.tramai.engine.approval.DefaultApprovalGateway",
            "dev.tramai.engine.approval.ApprovalGatewayRequestFactory",
            "dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest",
            "dev.tramai.engine.evidence.ProviderRoutingRuntimeEvidenceExporter"
        ),
        "tramai-security" to setOf(
            "dev.tramai.security.DefaultPolicyEngine",
            "dev.tramai.security.PolicyConfiguration",
            "dev.tramai.security.ProviderRoutingConfiguration",
            "dev.tramai.security.RuleBasedDlpInterceptor",
            "dev.tramai.security.approval.AllowAnyApprovalDecisionValidator",
            "dev.tramai.security.approval.DefaultApprovalGateCoordinator",
            "dev.tramai.security.approval.InMemoryApprovalContinuationStore",
            "dev.tramai.security.approval.InMemoryApprovalRecoveryCoordinator",
            "dev.tramai.security.approval.InMemoryApprovalStore",
            "dev.tramai.security.approval.RequireDistinctRequesterAndConsumer",
            "dev.tramai.security.approval.SecureRandomApprovalTokenGenerator",
            "dev.tramai.security.approval.Sha256ApprovalTokenDigester",
            "dev.tramai.security.approval.Sha256ToolArgumentsDigester",
            "dev.tramai.security.approval.StoredApprovalContinuation",
            "dev.tramai.security.approval.UuidApprovalIdGenerator",
            "dev.tramai.security.audit.AuditChainVerifier",
            "dev.tramai.security.audit.AuditEngine",
            "dev.tramai.security.audit.AuditEngineApprovalLifecycleAuditEmitter",
            "dev.tramai.security.audit.AuditEngineDlpRedactionAuditEmitter",
            "dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter",
            "dev.tramai.security.audit.AuditEvent",
            "dev.tramai.security.audit.AuditHashAlgorithm",
            "dev.tramai.security.audit.AuditSerializer",
            "dev.tramai.security.audit.AuditStore",
            "dev.tramai.security.audit.AuditStreamIdResolver",
            "dev.tramai.security.audit.InMemoryAuditStore",
            "dev.tramai.security.classification.ClassificationDecision",
            "dev.tramai.security.classification.ClassificationInput",
            "dev.tramai.security.classification.ClassificationRule",
            "dev.tramai.security.classification.DocumentClassifier",
            "dev.tramai.security.classification.RuleBasedDocumentClassifier",
            "dev.tramai.security.evidence.ManifestJsonReader",
            "dev.tramai.security.evidence.PolicyDecisionRuntimeEvidenceExporter",
            "dev.tramai.security.evidence.RuntimeEvidenceBundleWriter",
            "dev.tramai.security.evidence.RuntimeEvidenceContractValidator",
            "dev.tramai.security.evidence.RuntimeEvidenceJsonlWriter",
            "dev.tramai.security.evidence.RuntimeEvidenceRecord",
            "dev.tramai.security.evidence.ToolPermissionRuntimeEvidenceExporter",
            "dev.tramai.security.model.InMemoryModelRegistry",
            "dev.tramai.security.verification.FileSystemModelArtifactVerifier"
        ),
        "tramai-sovereign" to setOf(
            "dev.tramai.sovereign.SovereignDeploymentMode",
            "dev.tramai.sovereign.SovereignProfileConfiguration",
            "dev.tramai.sovereign.SovereignTramai",
            "dev.tramai.sovereign.evidence.ArtifactEvidenceV1",
            "dev.tramai.sovereign.evidence.AttestationEvidenceV1",
            "dev.tramai.sovereign.evidence.AuditChainEvidenceV1",
            "dev.tramai.sovereign.evidence.EvidenceSafeString",
            "dev.tramai.sovereign.evidence.ReleaseBundleEvidenceLoader",
            "dev.tramai.sovereign.evidence.ReleaseBundleEvidenceV1",
            "dev.tramai.sovereign.evidence.SovereignEvidencePackGenerator",
            "dev.tramai.sovereign.evidence.SovereignEvidencePackV1",
            "dev.tramai.sovereign.evidence.SovereignEvidencePackWriter",
            "dev.tramai.sovereign.evidence.SupplyChainEvidenceV1",
            "dev.tramai.sovereign.evidence.ZeroEgressEvidenceV1"
        ),
        "tramai-structured" to setOf(
            "dev.tramai.structured.JacksonStructuredOutputHandler"
        )
    )

    private val allKnownClasses: Set<String> by lazy {
        knownClasses.values.flatten().toSet()
    }

    private val configFile: File by lazy {
        // walk up from user.dir until we find config/quality/test-quality.yml
        var dir = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, "config/quality/test-quality.yml").isFile) {
            dir = dir.parentFile
        }
        checkNotNull(dir) {
            "config/quality/test-quality.yml not found from ${System.getProperty("user.dir")}"
        }
        File(dir, "config/quality/test-quality.yml")
    }

    private val configuration: TestQualityConfiguration by lazy {
        TestQualityConfiguration.parse(configFile, knownModules)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /** Check if a PIT class pattern matches any known class. */
    private fun patternMatchesKnownClass(pattern: String): Boolean {
        val stripped = pattern.removeSuffix("*")
        val prefix = stripped.removeSuffix(".*")
        return if (pattern.endsWith(".*")) {
            allKnownClasses.any { it.startsWith(prefix) }
        } else if (pattern.endsWith("*")) {
            // * suffix matches class + inner classes per PIT docs
            allKnownClasses.any { it == prefix || it.startsWith("$prefix$") }
        } else {
            pattern in allKnownClasses
        }
    }

    /** Build the set of matching known classes for a family's patterns. */
    private fun matchingKnownClasses(family: TestQualityConfiguration.MutationTargetFamily): Set<String> {
        val entries = mutableSetOf<String>()
        for (pattern in family.targetClasses) {
            val stripped = pattern.removeSuffix("*")
            val prefix = stripped.removeSuffix(".*")
            if (pattern.endsWith(".*")) {
                entries.addAll(allKnownClasses.filter { it.startsWith(prefix) })
            } else if (pattern.endsWith("*")) {
                // * suffix matches class + inner classes per PIT docs
                entries.addAll(allKnownClasses.filter { it == prefix || it.startsWith("$prefix$") })
            } else {
                if (pattern in allKnownClasses) entries.add(pattern)
            }
        }
        return entries
    }

    /** Return the module keys (e.g. "tramai-engine") whose known classes match the given pattern. */
    private fun modulesContainingPattern(pattern: String): Set<String> {
        val stripped = pattern.removeSuffix("*")
        val pkgPrefix = stripped.removeSuffix(".*")
        return knownClasses.filter { (_, classes) ->
            if (pattern.endsWith(".*")) {
                classes.any { it.startsWith(pkgPrefix) }
            } else if (pattern.endsWith("*")) {
                classes.any { it == pkgPrefix || it.startsWith("$pkgPrefix$") }
            } else {
                pattern in classes
            }
        }.keys
    }

    // ── Core validation tests ──────────────────────────────────────────

    @Test
    fun `configuration loads successfully`() {
        assertNotNull(configuration)
        assertTrue(configuration.mutation.targetFamilies.isNotEmpty())
    }

    @Test
    fun `each mutation family has unique targetClass patterns - no two families are identical`() {
        val families = configuration.mutation.targetFamilies
        val sets = families.mapValues { (_, family) -> family.targetClasses.toSet() }
        val seen = mutableMapOf<Set<String>, String>()
        for ((name, patterns) in sets) {
            val existing = seen.put(patterns, name)
            if (existing != null) {
                fail(
                    "Mutation families '$existing' and '$name' have identical targetClasses: ${patterns.sorted()}"
                )
            }
        }
    }

    @Test
    fun `every target class pattern matches at least one known class`() {
        val failures = mutableListOf<String>()
        for ((name, family) in configuration.mutation.targetFamilies) {
            val familyModuleKeys = family.modules.map { it.removePrefix(":") }.toSet()
            for (pattern in family.targetClasses) {
                if (!patternMatchesKnownClass(pattern)) {
                    failures += "Family '$name': pattern '$pattern' matches zero known classes"
                } else {
                    // Pattern must be consistent with the family's declared modules.
                    // A pattern like "dev.tramai.engine.*" should fail if the family
                    // doesn't include ":tramai-engine".
                    val matchingModules = modulesContainingPattern(pattern)
                    if (matchingModules.none { it in familyModuleKeys }) {
                        failures += "Family '$name': pattern '$pattern' matches classes only " +
                            "in modules $matchingModules, but family declares modules " +
                            "${family.modules}. Either add the missing module(s) or move " +
                            "the pattern to the correct family."
                    }
                }
            }
        }
        if (failures.isNotEmpty()) {
            fail("Pattern validation failures:\n  ${failures.joinToString("\n  ")}")
        }
    }

    @Test
    fun `no two families have identical sets of matching known classes`() {
        val matchSets = configuration.mutation.targetFamilies.mapValues { (_, family) ->
            matchingKnownClasses(family)
        }
        val seen = mutableMapOf<Set<String>, String>()
        for ((name, matches) in matchSets) {
            val existing = seen.put(matches, name)
            if (existing != null) {
                fail(
                    "Mutation families '$existing' and '$name' match the same set of " +
                        "${matches.size} known classes. Each family must target a distinct " +
                        "set of classes to justify a separate mutation budget.\n" +
                        "Overlapping classes: ${matches.sorted()}"
                )
            }
        }
    }

    @Test
    fun `classes matching each family are non-empty`() {
        for ((name, family) in configuration.mutation.targetFamilies) {
            val matches = matchingKnownClasses(family)
            assertTrue(
                matches.isNotEmpty(),
                "Family '$name' (modules=${family.modules}, patterns=${family.targetClasses}) " +
                    "matches zero known classes"
            )
        }
    }

    // ── Behavior-specific overlap guards ───────────────────────────────

    @Test
    fun `retry and routing targets are disjoint`() {
        val families = configuration.mutation.targetFamilies
        val retryMatches = matchingKnownClasses(families.getValue("retry"))
        val routingMatches = matchingKnownClasses(families.getValue("routing"))
        val overlap = retryMatches.intersect(routingMatches)
        assertTrue(
            overlap.isEmpty(),
            "Retry and routing must not overlap, but share these classes: ${overlap.sorted()}"
        )
    }

    @Test
    fun `tools targets do not overlap with routing targets`() {
        val families = configuration.mutation.targetFamilies
        val toolsMatches = matchingKnownClasses(families.getValue("tools"))
        val routingMatches = matchingKnownClasses(families.getValue("routing"))
        val overlap = toolsMatches.intersect(routingMatches)
        assertTrue(
            overlap.isEmpty(),
            "Tools and routing must not overlap, but share these classes: ${overlap.sorted()}"
        )
    }

    @Test
    fun `approval and policy targets are disjoint`() {
        val families = configuration.mutation.targetFamilies
        val approvalMatches = matchingKnownClasses(families.getValue("approval"))
        val policyMatches = matchingKnownClasses(families.getValue("policy"))
        val overlap = approvalMatches.intersect(policyMatches)
        assertTrue(
            overlap.isEmpty(),
            "Approval and policy must not overlap, but share these classes: ${overlap.sorted()}"
        )
    }

    @Test
    fun `policy and evidence targets are disjoint`() {
        val families = configuration.mutation.targetFamilies
        val policyMatches = matchingKnownClasses(families.getValue("policy"))
        val evidenceMatches = matchingKnownClasses(families.getValue("evidence"))
        val overlap = policyMatches.intersect(evidenceMatches)
        assertTrue(
            overlap.isEmpty(),
            "Policy and evidence must not overlap, but share these classes: ${overlap.sorted()}"
        )
    }
}
