package dev.tramai.core.exception

/**
 * Stable, machine-readable classification of provider HTTP and transport failures.
 *
 * The taxonomy is intentionally coarse: HTTP status, retryability, and retry
 * timing are carried by [ProviderException.statusCode],
 * [ProviderException.retryable], and [ProviderException.retryAfterMillis]
 * rather than being encoded as separate enum values.
 */
enum class ProviderFailureCode {
    /** The provider rejected the request with a non-2xx HTTP status. */
    HTTP_REJECTED,

    /** The request timed out at the transport layer. */
    TIMEOUT,

    /** The connection to the provider could not be established. */
    CONNECTION_FAILED,

    /** Another transport-level failure (I/O, protocol, SDK invocation). */
    TRANSPORT_FAILED,

    /** A failure that does not map to a known provider boundary. */
    UNEXPECTED_FAILURE,
}
