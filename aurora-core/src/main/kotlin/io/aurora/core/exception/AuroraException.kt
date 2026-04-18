package io.aurora.core.exception

sealed class AuroraException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class StructuredOutputException(
    message: String,
    val originalPrompt: String? = null,
    val lastRawResponse: String? = null,
    val validationError: String? = null,
    val attemptCount: Int? = null,
    cause: Throwable? = null,
) : AuroraException(message, cause)

class ProviderException(
    message: String,
    cause: Throwable? = null,
) : AuroraException(message, cause)

class ConfigurationException(
    message: String,
    cause: Throwable? = null,
) : AuroraException(message, cause)

class TimeoutException(
    message: String,
    cause: Throwable? = null,
) : AuroraException(message, cause)
