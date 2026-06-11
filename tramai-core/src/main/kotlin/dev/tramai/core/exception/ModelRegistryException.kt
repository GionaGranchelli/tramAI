package dev.tramai.core.exception

import dev.tramai.core.exception.TramaiException

open class ModelRegistryException(
    message: String,
    cause: Throwable? = null,
) : TramaiException(message, cause)

class ModelNotRegisteredException :
    ModelRegistryException("Requested model is not registered")

class ModelDisabledException :
    ModelRegistryException("Requested model is disabled")

class ModelRegistryUnavailableException :
    ModelRegistryException("Model registry is unavailable")

class ModelRegistryContractViolationException :
    ModelRegistryException("Model registry contract violation")

class CachedModelProvenanceMismatchException :
    ModelRegistryException("Cached response provenance does not match current model registry")
