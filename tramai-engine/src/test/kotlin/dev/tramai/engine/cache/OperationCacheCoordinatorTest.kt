package dev.tramai.engine.cache

import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.CachedModelProvenanceMismatchException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.model.ToolDefinition
import dev.tramai.core.observation.NoOpOperationInterceptor
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.engine.CachedOperationResult
import dev.tramai.engine.CachedResponseProvenance
import dev.tramai.engine.CacheSecurityPartition
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ModelRegistryEnforcer
import dev.tramai.engine.OperationCacheKey
import dev.tramai.engine.OperationResponseCache
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.ReturnKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Direct contract tests for [OperationCacheCoordinator].
 *
 * The cache protocol is a security-sensitive mini state machine. These tests
 * assert the frozen validation ORDER (envelope → partition → model
 * provenance → three reuse policy gates) and the eligibility gates (no
 * streaming, no tools, no conversation memory, no custom interceptors, no
 * DLP) using recording doubles — not mocks.
 */
class OperationCacheCoordinatorTest {

    private fun opWith(cacheable: Boolean = true): Operation = Operation(
        prompt = "",
        model = "model-x",
        provider = "",
        tools = emptyArray(),
        maxRetries = 0,
        cacheable = cacheable,
        cacheTtlMillis = 60_000,
    )

    private val security = ExecutionSecurityContext()
    private val partition = CacheSecurityPartition(null, null)
    private val fingerprint = "fp-1"

    private fun key() = OperationCacheKey(
        serviceInterface = "Svc",
        methodName = "m",
        requestedModel = "model-x",
        explicitProvider = null,
        requestDigest = "digest",
        operationFingerprint = fingerprint,
        securityPartition = partition,
    )

    private fun keyRequest(
        cacheable: Boolean = true,
        returnKind: ReturnKind = ReturnKind.STRING,
        tools: List<ToolDefinition> = emptyList(),
        digestSource: List<dev.tramai.core.model.Message> = listOf(dev.tramai.core.model.Message(dev.tramai.core.model.MessageRole.USER, "hi")),
        requestedModel: String = "model-x",
    ) = OperationCacheKeyRequest(
        digestSource = digestSource,
        securityPartition = partition,
        operationFingerprint = fingerprint,
        requestedModel = requestedModel,
        explicitProvider = null,
        serviceInterface = "Svc",
        methodName = "m",
        toolDefinitions = tools,
        operation = opWith(cacheable = cacheable),
        returnKind = returnKind,
    )

    private fun lookupRequest(
        cacheKey: OperationCacheKey = key(),
        conversationId: String? = null,
        correlationId: String = "cid",
    ) = OperationCacheLookupRequest(cacheKey, security, correlationId, conversationId)

    private fun provenance(
        providerId: String = "p1",
        modelName: String = "model-x",
        registryEntryId: String? = null,
        revision: String? = null,
        artifactDigest: ModelArtifactDigest? = null,
    ) = CachedResponseProvenance(providerId, modelName, null, null, registryEntryId, revision, artifactDigest)

    private fun cached(
        value: Any = "cached-value",
        provenance: CachedResponseProvenance = provenance(),
    ) = CachedOperationResult(value, provenance)

    private class RecordingCache : OperationResponseCache {
        val entries = mutableMapOf<OperationCacheKey, Pair<CachedOperationResult, Long>>()
        val invalidated = mutableListOf<OperationCacheKey>()
        val getCalls = AtomicInteger(0)
        val putCalls = AtomicInteger(0)

        override fun get(key: OperationCacheKey): CachedOperationResult? {
            getCalls.incrementAndGet()
            return entries[key]?.first
        }

        override fun put(key: OperationCacheKey, value: CachedOperationResult, ttlMillis: Long) {
            putCalls.incrementAndGet()
            entries[key] = value to ttlMillis
        }

        override fun invalidate(key: OperationCacheKey) {
            invalidated += key
            entries.remove(key)
        }
    }

    private class RecordingPolicyEngine(
        private val decision: PolicyDecision = PolicyDecision.Allow,
        private val cancelOn: String? = null,
        private val cancel: CancellationException = CancellationException("cancel"),
    ) : PolicyEngine {
        val evaluated = mutableListOf<String>()
        override suspend fun evaluate(context: dev.tramai.core.policy.PolicyContext): PolicyDecision {
            evaluated += context.enforcementPoint.name
            if (cancelOn == context.enforcementPoint.name) throw cancel
            return decision
        }
    }

    private fun coordinator(
        cache: OperationResponseCache = RecordingCache(),
        operationInterceptor: OperationInterceptor = NoOpOperationInterceptor,
        dlpInterceptor: DlpInterceptor = NoOpDlpInterceptor,
        modelRegistrySettings: ModelRegistrySettings = ModelRegistrySettings(enabled = false),
        registry: ModelRegistryEnforcer = ModelRegistryEnforcer(
            object : dev.tramai.core.model.ModelRegistry {
                override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? = null
            },
            ModelRegistrySettings(enabled = false),
        ),
        policyEngine: RecordingPolicyEngine = RecordingPolicyEngine(),
    ) = OperationCacheCoordinator(
        responseCache = cache,
        operationInterceptor = operationInterceptor,
        dlpInterceptor = dlpInterceptor,
        modelRegistrySettings = modelRegistrySettings,
        modelRegistryEnforcer = registry,
        policyHelper = PolicyEnforcementHelper(policyEngine, AtomicBoolean(false)),
    )

    private fun interceptor() = object : OperationInterceptor {}

    private fun dlp() = object : DlpInterceptor {
        override fun inspect(context: dev.tramai.core.security.DlpContext, text: String): dev.tramai.core.security.DlpResult =
            dev.tramai.core.security.DlpResult(text)
    }

    // ------------------------------------------------------------------
    // createKey eligibility
    // ------------------------------------------------------------------

    @Test
    fun `non-cacheable operation yields no key`() {
        val c = coordinator()
        assertThat(c.createKey(keyRequest(cacheable = false))).isNull()
    }

    @Test
    fun `streaming operation yields no key`() {
        val c = coordinator()
        assertThat(c.createKey(keyRequest(returnKind = ReturnKind.STREAMING))).isNull()
    }

    @Test
    fun `operation with tools yields no key`() {
        val c = coordinator()
        val tool = ToolDefinition(
            name = "t",
            description = "d",
            inputSchemaJson = """{"type":"object"}""",
        )
        assertThat(c.createKey(keyRequest(tools = listOf(tool)))).isNull()
    }

    @Test
    fun `custom interceptor yields no key`() {
        val c = coordinator(operationInterceptor = interceptor())
        assertThat(c.createKey(keyRequest())).isNull()
    }

    @Test
    fun `dlp interceptor yields no key`() {
        val c = coordinator(dlpInterceptor = dlp())
        assertThat(c.createKey(keyRequest())).isNull()
    }

    @Test
    fun `key includes effective messages digest, security partition and operation fingerprint`() {
        val c = coordinator()
        val k = c.createKey(keyRequest(digestSource = listOf(dev.tramai.core.model.Message(dev.tramai.core.model.MessageRole.USER, "hello"))))!!
        assertThat(k.serviceInterface).isEqualTo("Svc")
        assertThat(k.methodName).isEqualTo("m")
        assertThat(k.requestedModel).isEqualTo("model-x")
        assertThat(k.requestDigest).isEqualTo(dev.tramai.engine.buildRequestDigest(listOf(dev.tramai.core.model.Message(dev.tramai.core.model.MessageRole.USER, "hello"))))
        assertThat(k.operationFingerprint).isEqualTo(fingerprint)
        assertThat(k.securityPartition).isEqualTo(partition)
    }

    // ------------------------------------------------------------------
    // lookup validation order
    // ------------------------------------------------------------------

    @Test
    fun `valid hit crosses all three reuse policy gates in order`() = runTest {
        val cache = RecordingCache()
        cache.entries[key()] = cached() to 60_000L
        val policy = RecordingPolicyEngine()
        val c = coordinator(cache = cache, policyEngine = policy)

        val result = c.lookup(lookupRequest())

        assertThat(result).isEqualTo(OperationCacheLookupResult.Hit("cached-value"))
        assertThat(policy.evaluated).containsExactly(
            "BEFORE_PROVIDER_RESOLUTION",
            "BEFORE_PROVIDER_INVOCATION",
            "BEFORE_RESPONSE_RETURN",
        )
    }

    @Test
    fun `blank provider provenance is rejected and does not hit`() = runTest {
        val cache = RecordingCache()
        cache.entries[key()] = cached(provenance = provenance(providerId = "", modelName = "")) to 60_000L
        val c = coordinator(cache = cache)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.lookup(lookupRequest()) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("blank provider provenance")

        assertThat(cache.invalidated).isEmpty()
    }

    @Test
    fun `classification mismatch is rejected`() = runTest {
        val cache = RecordingCache()
        val partition = CacheSecurityPartition(dev.tramai.core.policy.DataClassification.CONFIDENTIAL, null)
        val key = key().copy(securityPartition = partition)
        cache.entries[key] = cached(provenance = provenance()) to 60_000L
        val c = coordinator(cache = cache)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.lookup(lookupRequest(cacheKey = key)) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("partition")
    }

    @Test
    fun `classification-source mismatch is rejected`() = runTest {
        val cache = RecordingCache()
        val partition = CacheSecurityPartition(null, dev.tramai.core.policy.ClassificationSource.DECLARED)
        val key = key().copy(securityPartition = partition)
        cache.entries[key] = cached(provenance = provenance()) to 60_000L
        val c = coordinator(cache = cache)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.lookup(lookupRequest(cacheKey = key)) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("partition")
    }

    @Test
    fun `current model provenance accepted when registry enabled`() = runTest {
        val cache = RecordingCache()
        val approved = RegisteredModel(
            registryEntryId = "entry-1",
            providerId = "p1",
            modelName = "model-x",
            revision = "rev-2",
            artifactDigest = ModelArtifactDigest.of("sha256:abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"),
        )
        val registry = ModelRegistryEnforcer(
            object : dev.tramai.core.model.ModelRegistry {
                override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? = approved
            },
            ModelRegistrySettings(enabled = true),
        )
        cache.entries[key()] = cached(
            provenance = provenance(registryEntryId = "entry-1", revision = "rev-2", artifactDigest = ModelArtifactDigest.of("sha256:abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd")),
        ) to 60_000L
        val policy = RecordingPolicyEngine()
        val c = coordinator(cache = cache, modelRegistrySettings = ModelRegistrySettings(enabled = true), registry = registry, policyEngine = policy)

        val result = c.lookup(lookupRequest())

        assertThat(result).isEqualTo(OperationCacheLookupResult.Hit("cached-value"))
    }

    @Test
    fun `model provenance drift invalidates and misses`() = runTest {
        val cache = RecordingCache()
        val approved = RegisteredModel(
            registryEntryId = "entry-1",
            providerId = "p1",
            modelName = "model-x",
            revision = "rev-NEW",
        )
        val registry = ModelRegistryEnforcer(
            object : dev.tramai.core.model.ModelRegistry {
                override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? = approved
            },
            ModelRegistrySettings(enabled = true),
        )
        cache.entries[key()] = cached(
            provenance = provenance(registryEntryId = "entry-1", revision = "rev-OLD"),
        ) to 60_000L
        val c = coordinator(cache = cache, modelRegistrySettings = ModelRegistrySettings(enabled = true), registry = registry)

        val result = c.lookup(lookupRequest())

        assertThat(result).isEqualTo(OperationCacheLookupResult.Miss(key()))
        assertThat(cache.invalidated).containsExactly(key())
        assertThat(cache.entries).isEmpty()
    }

    @Test
    fun `conversation memory in scope misses`() = runTest {
        val cache = RecordingCache()
        cache.entries[key()] = cached() to 60_000L
        val c = coordinator(cache = cache)

        val result = c.lookup(lookupRequest(conversationId = "cid"))

        assertThat(result).isEqualTo(OperationCacheLookupResult.Miss(null))
        assertThat(cache.getCalls.get()).isZero()
    }

    @Test
    fun `custom interceptor in scope misses without touching cache`() = runTest {
        val cache = RecordingCache()
        cache.entries[key()] = cached() to 60_000L
        val c = coordinator(cache = cache, operationInterceptor = interceptor())

        val result = c.lookup(lookupRequest())

        assertThat(result).isEqualTo(OperationCacheLookupResult.Miss(null))
        assertThat(cache.getCalls.get()).isZero()
    }

    @Test
    fun `dlp in scope misses without touching cache`() = runTest {
        val cache = RecordingCache()
        cache.entries[key()] = cached() to 60_000L
        val c = coordinator(cache = cache, dlpInterceptor = dlp())

        val result = c.lookup(lookupRequest())

        assertThat(result).isEqualTo(OperationCacheLookupResult.Miss(null))
        assertThat(cache.getCalls.get()).isZero()
    }

    @Test
    fun `empty cache misses with the key`() = runTest {
        val c = coordinator()
        val result = c.lookup(lookupRequest())
        assertThat(result).isEqualTo(OperationCacheLookupResult.Miss(key()))
    }

    @Test
    fun `policy deny propagates`() = runTest {
        val cache = RecordingCache()
        cache.entries[key()] = cached() to 60_000L
        val c = coordinator(
            cache = cache,
            policyEngine = RecordingPolicyEngine(
                decision = PolicyDecision.Deny(reason = "blocked", reasonCode = "CACHE_DENY"),
            ),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.lookup(lookupRequest()) } }
            .isInstanceOf(PolicyViolationException::class.java)
    }

    // ------------------------------------------------------------------
    // cancellation — never converted into miss/provenance/failure
    // ------------------------------------------------------------------

    @Test
    fun `cancellation during model authorization propagates as same exception`() = runTest {
        val cache = RecordingCache()
        cache.entries[key()] = cached() to 60_000L
        val approved = RegisteredModel("entry-1", "p1", "model-x", "rev-1")
        val cancel = CancellationException("auth-cancel")
        val registry = ModelRegistryEnforcer(
            object : dev.tramai.core.model.ModelRegistry {
                override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? =
                    throw cancel
            },
            ModelRegistrySettings(enabled = true),
        )
        val c = coordinator(cache = cache, modelRegistrySettings = ModelRegistrySettings(enabled = true), registry = registry)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.lookup(lookupRequest()) } }
            .isSameAs(cancel)
        assertThat(cache.invalidated).isEmpty()
    }

    @Test
    fun `cancellation during reuse policy resolution propagates`() = runTest {
        val cache = RecordingCache()
        cache.entries[key()] = cached() to 60_000L
        val cancel = CancellationException("policy-cancel")
        val c = coordinator(
            cache = cache,
            policyEngine = RecordingPolicyEngine(cancelOn = "BEFORE_PROVIDER_RESOLUTION", cancel = cancel),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.lookup(lookupRequest()) } }
            .isSameAs(cancel)
        assertThat(cache.invalidated).isEmpty()
    }

    @Test
    fun `cancellation during reuse invocation gate propagates`() = runTest {
        val cache = RecordingCache()
        cache.entries[key()] = cached() to 60_000L
        val cancel = CancellationException("policy-cancel")
        val c = coordinator(
            cache = cache,
            policyEngine = RecordingPolicyEngine(cancelOn = "BEFORE_PROVIDER_INVOCATION", cancel = cancel),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.lookup(lookupRequest()) } }
            .isSameAs(cancel)
        assertThat(cache.invalidated).isEmpty()
    }

    @Test
    fun `cancellation during reuse response gate propagates`() = runTest {
        val cache = RecordingCache()
        cache.entries[key()] = cached() to 60_000L
        val cancel = CancellationException("policy-cancel")
        val c = coordinator(
            cache = cache,
            policyEngine = RecordingPolicyEngine(cancelOn = "BEFORE_RESPONSE_RETURN", cancel = cancel),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.lookup(lookupRequest()) } }
            .isSameAs(cancel)
        assertThat(cache.invalidated).isEmpty()
    }

    // ------------------------------------------------------------------
    // store
    // ------------------------------------------------------------------

    @Test
    fun `store carries provider model security provenance and ttl`() {
        val cache = RecordingCache()
        val c = coordinator(cache = cache)
        val approved = RegisteredModel("entry-9", "p1", "model-x", "rev-3", ModelArtifactDigest.of("sha256:abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"))

        c.store(
            OperationCacheStoreRequest(
                key = key(),
                value = "result",
                providerId = "p1",
                modelName = "model-x",
                securityContext = ExecutionSecurityContext(),
                conversationId = null,
                approvedModel = approved,
                ttlMillis = 123_456L,
            ),
        )

        assertThat(cache.putCalls.get()).isEqualTo(1)
        val (stored, ttl) = cache.entries[key()]!!
        assertThat(stored.value).isEqualTo("result")
        assertThat(stored.provenance.providerId).isEqualTo("p1")
        assertThat(stored.provenance.modelName).isEqualTo("model-x")
        assertThat(stored.provenance.modelRegistryEntryId).isEqualTo("entry-9")
        assertThat(stored.provenance.modelRevision).isEqualTo("rev-3")
        assertThat(stored.provenance.modelArtifactDigest).isEqualTo(ModelArtifactDigest.of("sha256:abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"))
        assertThat(ttl).isEqualTo(123_456L)
    }

    @Test
    fun `store is skipped when conversation memory in scope`() {
        val cache = RecordingCache()
        val c = coordinator(cache = cache)

        c.store(
            OperationCacheStoreRequest(
                key(), "result", "p1", "model-x", ExecutionSecurityContext(), "cid", null, 60_000L,
            ),
        )

        assertThat(cache.putCalls.get()).isZero()
    }

    @Test
    fun `store is skipped when custom interceptor in scope`() {
        val cache = RecordingCache()
        val c = coordinator(cache = cache, operationInterceptor = interceptor())

        c.store(
            OperationCacheStoreRequest(
                key(), "result", "p1", "model-x", ExecutionSecurityContext(), null, null, 60_000L,
            ),
        )

        assertThat(cache.putCalls.get()).isZero()
    }

    @Test
    fun `store is skipped when dlp in scope`() {
        val cache = RecordingCache()
        val c = coordinator(cache = cache, dlpInterceptor = dlp())

        c.store(
            OperationCacheStoreRequest(
                key(), "result", "p1", "model-x", ExecutionSecurityContext(), null, null, 60_000L,
            ),
        )

        assertThat(cache.putCalls.get()).isZero()
    }
}
