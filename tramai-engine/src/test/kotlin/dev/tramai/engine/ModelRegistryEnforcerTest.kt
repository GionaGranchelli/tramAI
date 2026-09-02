package dev.tramai.engine

import dev.tramai.core.exception.ModelDisabledException
import dev.tramai.core.exception.ModelNotRegisteredException
import dev.tramai.core.exception.ModelRegistryUnavailableException
import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.RegisteredModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class ModelRegistryEnforcerTest {

    @Test
    fun `disabled registry enforcement returns null`() : Unit = runBlocking {
        val enforcer = ModelRegistryEnforcer(
            registry = registryReturning(registeredModel()),
            settings = ModelRegistrySettings(enabled = false),
        )

        assertThat(enforcer.authorize("provider-1", "model-1")).isNull()
    }

    @Test
    fun `enabled registry rejects unknown model`() : Unit = runBlocking {
        val enforcer = ModelRegistryEnforcer(
            registry = registryReturning(null),
            settings = ModelRegistrySettings(enabled = true),
        )

        assertThatThrownBy {
            runBlocking { enforcer.authorize("provider-1", "missing") }
        }.isInstanceOf(ModelNotRegisteredException::class.java)
    }

    @Test
    fun `enabled registry accepts registered model`() : Unit = runBlocking {
        val approved = registeredModel()
        val enforcer = ModelRegistryEnforcer(
            registry = registryReturning(approved),
            settings = ModelRegistrySettings(enabled = true),
        )

        assertThat(enforcer.authorize("provider-1", "model-1")).isEqualTo(approved)
    }

    @Test
    fun `enabled registry rejects disabled model`() : Unit = runBlocking {
        val enforcer = ModelRegistryEnforcer(
            registry = registryReturning(registeredModel(enabled = false)),
            settings = ModelRegistrySettings(enabled = true),
        )

        assertThatThrownBy {
            runBlocking { enforcer.authorize("provider-1", "model-1") }
        }.isInstanceOf(ModelDisabledException::class.java)
    }

    @Test
    fun `cancellation exception propagates`() : Unit = runBlocking {
        val enforcer = ModelRegistryEnforcer(
            registry = object : ModelRegistry {
                override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? {
                    throw CancellationException("cancelled")
                }
            },
            settings = ModelRegistrySettings(enabled = true),
        )

        assertThatThrownBy {
            runBlocking { enforcer.authorize("provider-1", "model-1") }
        }.isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `adapter exception is fully sanitized`() : Unit = runBlocking {
        val enforcer = ModelRegistryEnforcer(
            registry = object : ModelRegistry {
                override suspend fun findApprovedModel(
                    providerId: String,
                    modelName: String,
                ): RegisteredModel? {
                    throw IllegalStateException(
                        "token=secret-value url=http://internal-registry.local",
                    )
                }
            },
            settings = ModelRegistrySettings(enabled = true),
        )

        assertThatThrownBy {
            runBlocking { enforcer.authorize("provider-1", "model-1") }
        }.isInstanceOf(ModelRegistryUnavailableException::class.java)
            .hasMessage("Model registry is unavailable")
            .hasNoCause()
            .hasMessageNotContaining("secret-value")
            .hasMessageNotContaining("internal-registry")
    }

    // ── B2: real suspension/resumption discriminators (10.3c2) ──
    //
    // The tests above return/throw directly from findApprovedModel, so the
    // coroutine never actually suspends and resumes inside authorize(). PIT's
    // NO_COVERAGE mutants on the resume-path throwOnFailure (offset 167 /
    // line 18 block 14) can therefore not be killed by them. These three tests
    // force a genuine suspension: the registry signals entry, suspends on a
    // CompletableDeferred, and only resumes after the test observes the
    // suspended state — proving the continuation boundary, not a delay().

    private class SuspendingRegistry(
        private val onEntry: () -> Unit,
        private val outcome: () -> RegisteredModel?,
    ) : ModelRegistry {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? {
            onEntry()
            entered.complete(Unit)
            release.await()
            return outcome()
        }
    }

    @Test
    fun `resumed success returns the approved model`() : Unit = runBlocking {
        val approved = registeredModel()
        val registry = SuspendingRegistry(onEntry = {}, outcome = { approved })
        val enforcer = ModelRegistryEnforcer(
            registry = registry,
            settings = ModelRegistrySettings(enabled = true),
        )

        val outcome = CompletableDeferred<Result<RegisteredModel?>>()
        val job = launch {
            outcome.complete(runCatching { enforcer.authorize("provider-1", "model-1") })
        }
        registry.entered.await() // authorize() is genuinely suspended now
        registry.release.complete(Unit)
        assertThat(outcome.await().getOrNull()).isEqualTo(approved)
        job.join()
    }

    @Test
    fun `resumed adapter exception is sanitized`() : Unit = runBlocking {
        val registry = SuspendingRegistry(
            onEntry = {},
            outcome = { throw IllegalStateException("token=secret url=http://internal") },
        )
        val enforcer = ModelRegistryEnforcer(
            registry = registry,
            settings = ModelRegistrySettings(enabled = true),
        )

        val outcome = CompletableDeferred<Result<RegisteredModel?>>()
        val job = launch {
            outcome.complete(runCatching { enforcer.authorize("provider-1", "model-1") })
        }
        registry.entered.await()
        registry.release.complete(Unit)
        val failure = outcome.await().exceptionOrNull()
        assertThat(failure).isInstanceOf(ModelRegistryUnavailableException::class.java)
            .hasMessage("Model registry is unavailable")
            .hasNoCause()
        job.join()
    }

    @Test
    fun `resumed cancellation propagates unchanged`() : Unit = runBlocking {
        val registry = SuspendingRegistry(
            onEntry = {},
            outcome = { throw CancellationException("cancelled-after-resume") },
        )
        val enforcer = ModelRegistryEnforcer(
            registry = registry,
            settings = ModelRegistrySettings(enabled = true),
        )

        val outcome = CompletableDeferred<Result<RegisteredModel?>>()
        val job = launch {
            outcome.complete(runCatching { enforcer.authorize("provider-1", "model-1") })
        }
        registry.entered.await()
        registry.release.complete(Unit)
        val failure = outcome.await().exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
            .hasMessage("cancelled-after-resume")
        job.join()
    }


    private fun registeredModel(
        enabled: Boolean = true,
    ): RegisteredModel = RegisteredModel(
        registryEntryId = "entry-1",
        providerId = "provider-1",
        modelName = "model-1",
        revision = "rev-1",
        artifactDigest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
        enabled = enabled,
    )

    private fun registryReturning(model: RegisteredModel?): ModelRegistry = object : ModelRegistry {
        override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? = model
    }
}
