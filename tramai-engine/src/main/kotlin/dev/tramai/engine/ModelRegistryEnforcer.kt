package dev.tramai.engine

import dev.tramai.core.exception.ModelDisabledException
import dev.tramai.core.exception.ModelNotRegisteredException
import dev.tramai.core.exception.ModelRegistryContractViolationException
import dev.tramai.core.exception.ModelRegistryException
import dev.tramai.core.exception.ModelRegistryUnavailableException
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.RegisteredModel
import kotlinx.coroutines.CancellationException

internal class ModelRegistryEnforcer(
    private val registry: ModelRegistry,
    private val settings: ModelRegistrySettings,
) {
    suspend fun authorize(providerId: String, modelName: String): RegisteredModel? {
        if (!settings.enabled) return null

        val approved: RegisteredModel = try {
            registry.findApprovedModel(providerId, modelName)
                ?: throw ModelNotRegisteredException()
        } catch (e: ModelRegistryException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ModelRegistryUnavailableException(cause = e)
        }

        if (!approved.enabled) {
            throw ModelDisabledException()
        }
        if (approved.providerId != providerId || approved.modelName != modelName) {
            throw ModelRegistryContractViolationException(
                "Returned descriptor does not match requested provider-model",
            )
        }

        return approved
    }
}
