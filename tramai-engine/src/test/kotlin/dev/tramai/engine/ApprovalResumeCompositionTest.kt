package dev.tramai.engine

import dev.tramai.core.approval.ApprovalRecoveryCoordinator
import dev.tramai.core.approval.ApprovalResumeAdmission
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.engine.components.EngineComponentFactory
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * E9-COMP-001: workflow-bound resume admission requires the audited recovery coordinator.
 *
 * One invariant, one place — [EngineComponentFactory.create] is the composition boundary that already validates the
 * approval collaborators, so the resume pairing is validated there rather than a second time in `TramaiEngine`.
 */
class ApprovalResumeCompositionTest {
    private val admission = collaborator<ApprovalResumeAdmission>()

    private val recoveryCoordinator = collaborator<ApprovalRecoveryCoordinator>()

    @Test
    fun `admission without a recovery coordinator fails construction`() {
        val failure =
            assertFailsWith<ConfigurationException> {
                EngineComponentFactory.requireApprovalResumeComposition(admission, null)
            }

        assertEquals(
            "Workflow-bound approval resume admission requires an approval recovery coordinator: a refused claim " +
                "could not be closed through the audited recovery transition",
            failure.message,
        )
    }

    @Test
    fun `admission with a recovery coordinator is accepted`() {
        EngineComponentFactory.requireApprovalResumeComposition(admission, recoveryCoordinator)
    }

    @Test
    fun `neither collaborator keeps the standalone composition unchanged`() {
        EngineComponentFactory.requireApprovalResumeComposition(null, null)
    }

    @Test
    fun `recovery without admission stays supported`() {
        // The recovery coordinator is a standalone operator surface for stale-claim reconciliation; it does not depend
        // on workflow-bound admission, so a recovery-only deployment is a legitimate composition.
        EngineComponentFactory.requireApprovalResumeComposition(null, recoveryCoordinator)
    }

    @Test
    fun `an engine built with admission but no recovery coordinator is rejected at construction`() {
        val failure =
            assertFailsWith<ConfigurationException> {
                TramaiEngine(
                    providerRegistry = ProviderRegistry.singleProvider(registryTestProvider()),
                    approvalResumeAdmission = admission,
                )
            }

        assertNotNull(failure.message)
    }

    @Test
    fun `an engine with neither collaborator still constructs`() {
        val engine = TramaiEngine(providerRegistry = ProviderRegistry.singleProvider(registryTestProvider()))

        assertNotNull(engine)
    }

    private fun registryTestProvider(): ModelProvider =
        object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse = ModelResponse(content = "test")

            override fun providerId(): String = "test-provider"
        }

    private inline fun <reified T : Any> collaborator(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
            throw UnsupportedOperationException("collaborator call is not expected")
        } as T
}
