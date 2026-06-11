package dev.tramai.core.exception

import dev.tramai.core.exception.TramaiException

open class ModelRegistryException(
    message: String,
    cause: Throwable? = null,
) : TramaiException(message, cause)

class ModelNotRegisteredException(
    providerId: String,
    modelName: String,
) : ModelRegistryException("Model '$modelName' from provider '$providerId' is not registered")

class ModelDisabledException(
    registryEntryId: String,
) : ModelRegistryException("Registered model '$registryEntryId' is disabled")

class ModelRegistryUnavailableException(
    cause: Throwable? = null,
) : ModelRegistryException("Model registry is unavailable", cause)

class ModelRegistryContractViolationException(
    reason: String,
) : ModelRegistryException("Model registry contract violation: $reason")

class CachedModelProvenanceMismatchException : ModelRegistryException(
    "Cached response provenance does not match current model registry",
)
