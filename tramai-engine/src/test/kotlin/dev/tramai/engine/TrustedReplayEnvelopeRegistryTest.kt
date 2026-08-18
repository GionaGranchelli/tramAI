package dev.tramai.engine

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.engine.components.EngineComponentFactory
import dev.tramai.engine.invocation.InvocationExecutionCoordinator
import dev.tramai.engine.planning.OperationExecutionPlan
import dev.tramai.engine.planning.OperationFingerprintFactory
import dev.tramai.engine.planning.ServiceDefinition
import dev.tramai.core.memory.UuidConversationIdProvider
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolDefinition
import dev.tramai.core.observation.NoOpOperationInterceptor
import dev.tramai.core.observation.NoOpOperationObserver
import dev.tramai.core.observation.NoOpToolFailureDiagnosticObserver
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.core.security.PromptSanitizer
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.policy.NoOpPolicyDecisionAuditEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests for PR #28 types: ReplayEnvelopeFactory, ResumeOperationRegistry,
 * JvmMethodDescriptorHelper.
 */
class TrustedReplayEnvelopeRegistryTest {

    // ── ReplayEnvelopeFactory tests ──────────────────────────────────

    @Test
    fun `prepareForSuspension redacts selected slot`() {
        val toolCallId = "call-1"
        val toolName = "lookup"
        val opRef = testOpRef()
        val messages = listOf(
            Message(role = MessageRole.USER, content = "hello"),
            Message(
                role = MessageRole.ASSISTANT, content = "",
                toolCalls = listOf(ToolCall(id = toolCallId, name = toolName, argumentsJson = """{"input":"secret"}""")),
            ),
        )
        val prepared = ReplayEnvelopeFactory.prepareForSuspension(opRef, messages, toolCallId, toolName, 0)
        val payload = prepared.envelope.revealForResume()
        val tc = payload.messages.last { it.role == MessageRole.ASSISTANT && it.toolCalls != null }.toolCalls!![0]
        assertThat(tc.argumentsJson).isEqualTo(REDACTED_APPROVAL_CONTINUATION_ARGUMENTS)
        assertThat(tc.id).isEqualTo(toolCallId)
        assertThat(tc.name).isEqualTo(toolName)
    }

    @Test
    fun `digest computed from redacted snapshot differs from raw`() {
        val opRef = testOpRef()
        val messages = listOf(assistantMsg(ToolCall("c1", "lookup", """{"x":"secret"}""")))
        val rawDigest = ReplayEnvelopeDigestHelper.compute(opRef, messages)
        val prepared = ReplayEnvelopeFactory.prepareForSuspension(opRef, messages, "c1", "lookup", 0)
        assertThat(prepared.digest).isNotEqualTo(rawDigest)
    }

    @Test
    fun `rehydration restores claimed arguments`() {
        val opRef = testOpRef()
        val messages = listOf(assistantMsg(ToolCall("c1", "lookup", """{"x":"secret"}""")))
        val prepared = ReplayEnvelopeFactory.prepareForSuspension(opRef, messages, "c1", "lookup", 0)
        val payload = prepared.envelope.revealForResume()
        val meta = testMetadata(opRef, prepared.digest, "c1", "lookup", 0)
        val rehydrated = ReplayEnvelopeFactory.rehydrateAfterClaim(payload, meta, """{"x":"restored"}""")
        val tc = rehydrated.messages.last { it.role == MessageRole.ASSISTANT && it.toolCalls != null }.toolCalls!![0]
        assertThat(tc.argumentsJson).isEqualTo("""{"x":"restored"}""")
    }

    @Test
    fun `historic duplicate same id same name fails`() {
        val opRef = testOpRef()
        val msgs = listOf(assistantMsg(ToolCall("c1", "lookup", """{"x":"1"}""")), assistantMsg(ToolCall("c1", "lookup", """{"x":"2"}""")))
        assertThatThrownBy { ReplayEnvelopeFactory.prepareForSuspension(opRef, msgs, "c1", "lookup", 0) }
            .hasMessageContaining("replay-envelope-duplicate-tool-call-id")
    }

    @Test
    fun `historic duplicate same id different name fails`() {
        val opRef = testOpRef()
        val msgs = listOf(assistantMsg(ToolCall("c1", "lookupCustomer", """{"x":"1"}""")), assistantMsg(ToolCall("c1", "executePayment", """{"x":"2"}""")))
        assertThatThrownBy { ReplayEnvelopeFactory.prepareForSuspension(opRef, msgs, "c1", "executePayment", 0) }
            .hasMessageContaining("replay-envelope-duplicate-tool-call-id")
    }

    @Test
    fun `historic duplicate same id different name fails rehydration`() {
        val opRef = testOpRef()
        val msgs = listOf(assistantMsg(ToolCall("c1", "lookupCustomer", """{"x":"hist"}""")), assistantMsg(ToolCall("c1", "executePayment", REDACTED_APPROVAL_CONTINUATION_ARGUMENTS)))
        val payload = ReplayPayload(messages = msgs)
        val meta = testMetadata(opRef, Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"), "c1", "executePayment", 0)
        assertThatThrownBy { ReplayEnvelopeFactory.rehydrateAfterClaim(payload, meta, "{}") }
            .hasMessageContaining("replay-envelope-duplicate-tool-call-id")
    }

    @Test
    fun `missing assistant batch fails`() {
        assertThatThrownBy { ReplayEnvelopeFactory.prepareForSuspension(testOpRef(), listOf(Message.text("hi")), "c1", "t", 0) }
            .hasMessageContaining("replay-envelope-assistant-batch-not-found")
    }

    @Test
    fun `corrupt index fails rehydration`() {
        val msgs = listOf(assistantMsg(ToolCall("c1", "t", REDACTED_APPROVAL_CONTINUATION_ARGUMENTS)))
        val meta = testMetadata(testOpRef(), Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"), "c1", "t", 5)
        assertThatThrownBy { ReplayEnvelopeFactory.rehydrateAfterClaim(ReplayPayload(msgs), meta, "{}") }
            .hasMessageContaining("replay-envelope-tool-call-index-out-of-bounds")
    }

    @Test
    fun `corrupt id fails rehydration`() {
        val msgs = listOf(assistantMsg(ToolCall("wrong", "t", REDACTED_APPROVAL_CONTINUATION_ARGUMENTS)))
        val meta = testMetadata(testOpRef(), Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"), "c1", "t", 0)
        assertThatThrownBy { ReplayEnvelopeFactory.rehydrateAfterClaim(ReplayPayload(msgs), meta, "{}") }
            .hasMessageContaining("replay-envelope-tool-call-id-mismatch")
    }

    @Test
    fun `different ids same name remains valid`() {
        val opRef = testOpRef()
        val msgs = listOf(assistantMsg(ToolCall("c1", "lookup", """{"x":"1"}""")), assistantMsg(ToolCall("c2", "lookup", """{"x":"2"}""")))
        val prepared = ReplayEnvelopeFactory.prepareForSuspension(opRef, msgs, "c2", "lookup", 0)
        val payload = prepared.envelope.revealForResume()
        val tc = payload.messages.last { it.role == MessageRole.ASSISTANT }.toolCalls!![0]
        assertThat(tc.id).isEqualTo("c2")
        assertThat(tc.argumentsJson).isEqualTo(REDACTED_APPROVAL_CONTINUATION_ARGUMENTS)
    }

    @Test
    fun `extra sentinel in historical message fails rehydration`() {
        val opRef = testOpRef()
        val msgs = listOf(
            assistantMsg(ToolCall("c2", "other", REDACTED_APPROVAL_CONTINUATION_ARGUMENTS)),
            assistantMsg(ToolCall("c1", "lookup", REDACTED_APPROVAL_CONTINUATION_ARGUMENTS)),
        )
        val payload = ReplayPayload(messages = msgs)
        val meta = testMetadata(opRef,
            Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
            "c1", "lookup", 0)
        assertThatThrownBy { ReplayEnvelopeFactory.rehydrateAfterClaim(payload, meta, "{}") }
            .hasMessageContaining("replay-envelope-redaction-count-mismatch")
    }

    // ── ResumeOperationRegistry tests ────────────────────────────────

    @Test
    fun `registry missing key fails`() {
        val registry = ResumeOperationRegistry()
        assertThatThrownBy { registry.resolve(testOpRef()) }
            .hasMessageContaining("resume-operation-not-registered")
    }

    @Test
    fun `registry conflict cannot partially publish`() {
        // Construct a multi-operation ServiceDefinition where one op conflicts
        val registry = ResumeOperationRegistry()
        val svcClass = AtomicSvc::class.java
        val fooMethod = svcClass.getMethod("foo")
        val barMethod = svcClass.getMethod("bar")
        val svcQualifiedName = AtomicSvc::class.qualifiedName ?: ""

        // Define original foo and bar OperationDefinition instances
        val origFooOp = operationAnnotation("gpt-4", "original foo prompt")
        val conflictFooOp = operationAnnotation("gpt-4", "different foo prompt -- digest differs")
        val barOp = operationAnnotation("gpt-4", "do bar task")
        val emptyTools = emptyList<ToolDefinition>()

        val origFooDef = OperationDefinition(
            method = fooMethod, operation = origFooOp,
            classLevelSystemPrompt = null, systemAnnotations = emptyList(),
            userAnnotations = emptyList(), isSuspend = false,
            parameterNames = emptyList(), returnKind = ReturnKind.STRING,
            returnType = null, returnTypeDescription = "String",
            toolDefinitions = emptyTools, promptSanitizer = null,
        )
        val conflictFooDef = OperationDefinition(
            method = fooMethod, operation = conflictFooOp,
            classLevelSystemPrompt = null, systemAnnotations = emptyList(),
            userAnnotations = emptyList(), isSuspend = false,
            parameterNames = emptyList(), returnKind = ReturnKind.STRING,
            returnType = null, returnTypeDescription = "String",
            toolDefinitions = emptyTools, promptSanitizer = null,
        )
        val barDef = OperationDefinition(
            method = barMethod, operation = barOp,
            classLevelSystemPrompt = null, systemAnnotations = emptyList(),
            userAnnotations = emptyList(), isSuspend = false,
            parameterNames = emptyList(), returnKind = ReturnKind.STRING,
            returnType = null, returnTypeDescription = "String",
            toolDefinitions = emptyTools, promptSanitizer = null,
        )

        val fooOnlySvc = ServiceDefinition(svcClass.kotlin, null, mapOf(fooMethod to plan(origFooDef)))
        val handler = dummyHandler(fooOnlySvc, registry)

        // Register foo with the original definition
        val registeredRef = registry.register(fooOnlySvc, origFooDef, handler)

        // RegisterAll with foo (conflicting) + bar (new)
        val bulkSvc = ServiceDefinition(svcClass.kotlin, null, mapOf(
            fooMethod to plan(conflictFooDef),
            barMethod to plan(barDef),
        ))
        val bulkHandler = dummyHandler(bulkSvc, registry)

        assertThatThrownBy { registry.registerAll(bulkSvc, bulkHandler) }
            .hasMessageContaining("resume-operation-registration-conflict")

        // Foo is still resolvable (previous snapshot unchanged)
        val stillRegistered = registry.resolve(registeredRef)
        assertThat(stillRegistered).isNotNull
        assertThat(stillRegistered.reference.methodName).isEqualTo("foo")

        // Bar was NOT added by the failed registerAll
        val barRef = ResumeOperationReference(
            serviceInterface = svcQualifiedName,
            methodName = "bar",
            jvmMethodDescriptor = JvmMethodDescriptorHelper.compute(barMethod),
            resumeDefinitionDigest = ResumeDefinitionDigestHelper.compute(bulkSvc, barDef),
        )
        assertThatThrownBy { registry.resolve(barRef) }
            .hasMessageContaining("resume-operation-not-registered")
    }

    @Test
    fun `identical registerAll call is idempotent`() {
        val registry = ResumeOperationRegistry()
        val svcClass = AtomicSvc::class.java
        val fooMethod = svcClass.getMethod("foo")
        val barMethod = svcClass.getMethod("bar")
        val svcQualifiedName = AtomicSvc::class.qualifiedName ?: ""
        val op = operationAnnotation("gpt-4", "idempotent test")
        val emptyTools = emptyList<ToolDefinition>()

        val fooDef = OperationDefinition(
            method = fooMethod, operation = op,
            classLevelSystemPrompt = null, systemAnnotations = emptyList(),
            userAnnotations = emptyList(), isSuspend = false,
            parameterNames = emptyList(), returnKind = ReturnKind.STRING,
            returnType = null, returnTypeDescription = "String",
            toolDefinitions = emptyTools, promptSanitizer = null,
        )
        val barDef = OperationDefinition(
            method = barMethod, operation = op,
            classLevelSystemPrompt = null, systemAnnotations = emptyList(),
            userAnnotations = emptyList(), isSuspend = false,
            parameterNames = emptyList(), returnKind = ReturnKind.STRING,
            returnType = null, returnTypeDescription = "String",
            toolDefinitions = emptyTools, promptSanitizer = null,
        )
        val svcDef = ServiceDefinition(svcClass.kotlin, null, mapOf(
            fooMethod to plan(fooDef),
            barMethod to plan(barDef),
        ))
        val handler = dummyHandler(svcDef, registry)

        // First call succeeds
        registry.registerAll(svcDef, handler)
        val fooRef = ResumeOperationReference(
            serviceInterface = svcQualifiedName,
            methodName = "foo",
            jvmMethodDescriptor = JvmMethodDescriptorHelper.compute(fooMethod),
            resumeDefinitionDigest = ResumeDefinitionDigestHelper.compute(svcDef, fooDef),
        )
        assertThat(registry.resolve(fooRef)).isNotNull

        // Second identical call must not throw
        registry.registerAll(svcDef, handler)

        // Both operations still resolvable
        assertThat(registry.resolve(fooRef)).isNotNull
        val barRef = ResumeOperationReference(
            serviceInterface = svcQualifiedName,
            methodName = "bar",
            jvmMethodDescriptor = JvmMethodDescriptorHelper.compute(barMethod),
            resumeDefinitionDigest = ResumeDefinitionDigestHelper.compute(svcDef, barDef),
        )
        assertThat(registry.resolve(barRef)).isNotNull
    }

    // ── JvmMethodDescriptorHelper tests ──────────────────────────────

    class NestedInput

    @Test
    fun `nested class uses binary name`() {
        val m = TestSvc::class.java.methods.find { it.name == "nestedParam" }!!
        val d = JvmMethodDescriptorHelper.compute(m)
        assertThat(d).contains("TrustedReplayEnvelopeRegistryTest\$NestedInput")
        assertThat(d).doesNotContain("TrustedReplayEnvelopeRegistryTest.NestedInput")
    }

    @Test
    fun `primitive arrays`() {
        val m = TestSvc::class.java.methods.find { it.name == "arrayParams" }!!
        val d = JvmMethodDescriptorHelper.compute(m)
        assertThat(d).contains("([I")
        assertThat(d).contains("[Ljava/lang/String;")
    }

    // ── Test interfaces ──────────────────────────────────────────────

    private interface TestSvc {
        fun nestedParam(input: NestedInput): String
        fun arrayParams(ints: IntArray, strings: Array<String>): Unit
    }

    private interface AtomicSvc {
        fun foo(): String
        fun bar(): String
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun testOpRef() = ResumeOperationReference("t.S", "m", "()V",
        Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"))

    private fun testMetadata(opRef: ResumeOperationReference, digest: Sha256Digest, id: String, name: String, idx: Int) =
        SuspendedInvocationMetadata(
            approvalId = "a1", toolCallId = id, toolName = name, toolCallIndex = idx,
            correlationId = "c1",
            identity = EngineExecutionIdentity("wf1", "c1",
                Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001"),
                "1.0", "admin"),
            securityContext = dev.tramai.engine.ExecutionSecurityContext(),
            operationReference = opRef, replayEnvelopeDigest = digest,
            toolReference = ResumeToolReference(name,
                Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000001")),
        )

    private fun assistantMsg(tc: ToolCall) = Message(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(tc))

    /** Creates a dynamic proxy [dev.tramai.core.annotations.Operation] with the given values. */
    private fun operationAnnotation(model: String, prompt: String): dev.tramai.core.annotations.Operation {
        return Proxy.newProxyInstance(
            dev.tramai.core.annotations.Operation::class.java.classLoader,
            arrayOf(dev.tramai.core.annotations.Operation::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "prompt" -> prompt
                    "model" -> model
                    "provider" -> ""
                    "tools" -> emptyArray<String>()
                    "maxRetries" -> 0
                    "providerRetries" -> 0
                    "timeoutMillis" -> 30_000L
                    "cacheable" -> false
                    "cacheTtlMillis" -> 60_000L
                    "annotationType" -> dev.tramai.core.annotations.Operation::class.java
                    "equals" -> proxy === args!![0]
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "@Operation(model=$model, prompt=$prompt)"
                    else -> method.defaultValue
                }
            },
        ) as dev.tramai.core.annotations.Operation
    }

    /** Creates a [InvocationExecutionCoordinator] with minimal viable defaults (never dereferenced during registration). */
    private fun dummyHandler(svcDef: ServiceDefinition, registry: ResumeOperationRegistry): InvocationExecutionCoordinator {
        val components = EngineComponentFactory.create(
            providerRegistry = ProviderRegistry.builder().build(),
            structuredOutputHandler = null,
            toolRegistry = ToolRegistry(),
            operationObserver = NoOpOperationObserver,
            operationInterceptor = NoOpOperationInterceptor,
            responseCache = NoOpOperationResponseCache,
            modelRegistry = dev.tramai.core.model.NoOpModelRegistry,
            modelRegistrySettings = dev.tramai.core.model.ModelRegistrySettings(),
            circuitBreakerSettings = CircuitBreakerSettings(),
            retryPolicySettings = RetryPolicySettings(),
            tokenBudgetSettings = TokenBudgetSettings(),
            promptSanitizer = null,
            chatMemory = null,
            conversationIdProvider = UuidConversationIdProvider(),
            policyEngine = PolicyEngine { _ -> PolicyDecision.Allow },
            dlpInterceptor = NoOpDlpInterceptor,
            dlpRedactionAuditEmitter = NoOpDlpRedactionAuditEmitter,
            toolResultFilteringSettings = ToolResultFilteringSettings(),
            engineEventObserver = NoOpEngineEventObserver,
            toolFailureDiagnosticObserver = NoOpToolFailureDiagnosticObserver,
            policyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
            suspendedInvocationStore = InMemorySuspendedInvocationStore(),
            approvalContinuationStore = null,
            toolArgumentsDigester = null,
            approvalGateCoordinator = null,
            approvalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
            clock = Clock.systemUTC(),
        )
        return InvocationExecutionCoordinator(
            components = components,
            circuitBreaker = ProviderCircuitBreaker(CircuitBreakerSettings()),
            retryDelayPolicy = ProviderRetryDelayPolicy(RetryPolicySettings()),
            migrationWarningGuard = AtomicBoolean(false),
            lifecycleScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            serviceDefinition = svcDef,
            resumeOperationRegistry = registry,
        )
    }

    private fun plan(definition: OperationDefinition) = OperationExecutionPlan(
        definition = definition,
        fingerprint = OperationFingerprintFactory().create(definition.toolDefinitions, definition.operation),
        serviceInterface = definition.method.declaringClass.name,
        methodName = definition.method.name,
    )
}
