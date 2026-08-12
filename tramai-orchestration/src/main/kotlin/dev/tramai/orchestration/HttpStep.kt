package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.IDN
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String?,
)

data class HttpStepConfig(
    val timeoutSeconds: Long = 30,
    val maxResponseBytes: Long = 1_048_576,
    val retryOnStatus: Set<Int> = emptySet(),
    val maxRetries: Int = 0,
    val allowedHosts: Set<String>? = null,
) {
    init {
        require(timeoutSeconds > 0) { "HttpStepConfig.timeoutSeconds must be greater than zero" }
        require(maxResponseBytes >= 0) { "HttpStepConfig.maxResponseBytes must be zero or greater" }
        require(maxResponseBytes <= Int.MAX_VALUE.toLong()) {
            "HttpStepConfig.maxResponseBytes must be less than or equal to ${Int.MAX_VALUE}"
        }
        require(maxRetries >= 0) { "HttpStepConfig.maxRetries must be zero or greater" }
    }
}

/**
 * Public failure type for failed HTTP workflow steps.
 *
 * The public constructor is for application-facing construction and takes the
 * caller's own [redactedUrl] verbatim — callers are responsible for redaction.
 * Failures produced by TramAI itself always use the internal safe factory,
 * which emits the fixed safe message with a `<redacted>` URL and no cause, so
 * raw URLs, hostnames, and policy details never cross the public boundary.
 */
class WorkflowHttpException : RuntimeException {
    val stepName: String
    val redactedUrl: String
    val attempt: Int
    constructor(stepName: String, redactedUrl: String, attempt: Int, cause: Throwable) :
        super("Workflow HTTP step '$stepName' failed for '$redactedUrl' on attempt $attempt: ${cause.message ?: cause::class.java.simpleName}", cause) {
        this.stepName = stepName
        this.redactedUrl = redactedUrl
        this.attempt = attempt
    }
    var failureCode: WorkflowStepFailureCode? = null
        internal set
    internal var safeFactoryTrusted: Boolean = false

    internal constructor(stepName: String, attempt: Int, safeMessage: String, safe: Boolean) : super(safeMessage) {
        this.stepName = stepName
        this.redactedUrl = "<redacted>"
        this.attempt = attempt
    }
}

internal object WorkflowHttpClients {
    val default: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
}

internal enum class HttpTransportCapability {
    PRE_CONNECT_VALIDATION,
    CONNECTED_ADDRESS_VALIDATION,
}

internal class ControlledSendResult(
    val response: java.net.http.HttpResponse<java.io.InputStream>,
)

internal interface HttpTransport {
    val capability: HttpTransportCapability
    suspend fun send(
        httpRequest: java.net.http.HttpRequest,
        blockingDispatcher: CoroutineContext,
        onConnected: (InetAddress) -> Unit,
    ): ControlledSendResult
}

internal class JdkHttpTransport(private val httpClient: HttpClient) : HttpTransport {
    override val capability = HttpTransportCapability.PRE_CONNECT_VALIDATION

    override suspend fun send(
        httpRequest: java.net.http.HttpRequest,
        blockingDispatcher: CoroutineContext,
        onConnected: (InetAddress) -> Unit,
    ): ControlledSendResult = withContext(blockingDispatcher) {
        if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw HttpPolicyViolation("redirect-following HttpClient is not permitted for outbound HTTP steps")
        }
        ControlledSendResult(httpClient.send(httpRequest, BodyHandlers.ofInputStream()))
    }
}

internal data class HttpWorkflowStep<S>(
    override val name: String,
    val requestBuilder: suspend (S, WorkflowContext) -> HttpRequest,
    val merge: suspend (S, HttpResponse, WorkflowContext) -> S,
    val config: HttpStepConfig = HttpStepConfig(),
    val blockingDispatcher: CoroutineContext = Dispatchers.IO,
) : InternalWorkflowStep<S> {
    suspend fun execute(
        workflowName: String,
        state: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
        transport: HttpTransport,
        policy: OutboundNetworkPolicy,
        failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver = NoOpWorkflowStepFailureDiagnosticObserver,
    ): S {
        val request = try {
            requestBuilder(state, context)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw failure(workflowName, error, WorkflowStepFailureCode.PREPARATION_FAILED, 1, false, failureDiagnosticObserver)
        }
        val method = request.method.trim().uppercase()
        val redactedUrl = "<redacted>"
        val canonicalRequest = try {
            validateMethod(method, request.method)
            canonicalizeRequestUri(request.url)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            val code = WorkflowStepFailureCode.VALIDATION_FAILED
            observer.onWorkflowEvent(
                workflowName = workflowName,
                name = "tramai.workflow.http.request.validation.failed",
                attributes = mapOf(
                    "step_name" to name,
                    "failure_code" to code.value,
                ),
                context = context,
            )
            throw failure(workflowName, error, code, 1, false, failureDiagnosticObserver)
        }
        try {
            policy.validateTarget(canonicalRequest.target)
            val resolved = try {
                resolveHostAddresses(canonicalRequest.target.host)
            } catch (error: java.net.UnknownHostException) {
                error.rethrowIfCancellation()
                throw HttpPolicyViolation("outbound host could not be resolved")
            }
            policy.validateTarget(canonicalRequest.target.copy(addresses = resolved))
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            observer.onWorkflowEvent(
                workflowName = workflowName,
                name = "tramai.workflow.http.request.policy.rejected",
                attributes = mapOf(
                    "step_name" to name,
                    "failure_code" to WorkflowStepFailureCode.POLICY_REJECTED.value,
                ),
                context = context,
            )
            throw failure(workflowName, error, WorkflowStepFailureCode.POLICY_REJECTED, 1, false, failureDiagnosticObserver)
        }

        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.http.request.started",
            attributes = mapOf(
                "step_name" to name,
                "http_method" to method,
            ),
            context = context,
        )

        var retryAttempt = 0
        while (true) {
            val attemptNumber = retryAttempt + 1
            val response = try {
                executeRequest(
                    HttpRequestExecution(
                        request = request,
                        uri = canonicalRequest.uri,
                        target = canonicalRequest.target,
                        method = method,
                        observer = observer,
                        workflowName = workflowName,
                        context = context,
                        transport = transport,
                        policy = policy,
                        redactedUrl = redactedUrl,
                    ),
                )
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                val code = when (error) {
                    is HttpPolicyViolation -> WorkflowStepFailureCode.POLICY_REJECTED
                    is java.net.http.HttpTimeoutException -> WorkflowStepFailureCode.TIMEOUT
                    else -> WorkflowStepFailureCode.TRANSPORT_FAILED
                }
                if (code == WorkflowStepFailureCode.POLICY_REJECTED) {
                    observer.onWorkflowEvent(
                        workflowName = workflowName,
                        name = "tramai.workflow.http.request.policy.rejected",
                        attributes = mapOf(
                            "step_name" to name,
                            "failure_code" to code.value,
                        ),
                        context = context,
                    )
                }
                throw failure(workflowName, error, code, attemptNumber, false, failureDiagnosticObserver)
            }

            observer.onWorkflowEvent(
                workflowName = workflowName,
                name = "tramai.workflow.http.request.completed",
                attributes = mapOf(
                    "step_name" to name,
                    "http_method" to method,
                    "status_code" to response.status,
                    "response_size_bytes" to response.responseSizeBytes,
                ),
                context = context,
            )
            if (response.status in config.retryOnStatus && retryAttempt < config.maxRetries) {
                val nextDelayMillis = jitteredDelayMillis(retryAttempt)
                observer.onWorkflowEvent(
                    workflowName = workflowName,
                    name = "tramai.workflow.http.request.retrying",
                    attributes = mapOf(
                        "step_name" to name,
                        "http_method" to method,
                        "status_code" to response.status,
                        "retry_attempt" to (retryAttempt + 1),
                        "next_delay_ms" to nextDelayMillis,
                    ),
                    context = context,
                )
                delay(nextDelayMillis)
                retryAttempt += 1
                continue
            }
            return try {
                merge(state, response.toWorkflowResponse(), context)
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                throw failure(workflowName, error, WorkflowStepFailureCode.RESULT_HANDLING_FAILED, attemptNumber, false, failureDiagnosticObserver)
            }
        }
    }

    private suspend fun executeRequest(execution: HttpRequestExecution): ExecutedHttpResponse {
        val request = execution.request
        val uri = execution.uri
        val method = execution.method
        val observer = execution.observer
        val workflowName = execution.workflowName
        val context = execution.context
        val bodyPublisher = request.body?.let(BodyPublishers::ofString) ?: BodyPublishers.noBody()
        val httpRequest = java.net.http.HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(config.timeoutSeconds))
            .method(method, bodyPublisher)
            .apply {
                request.headers.forEach { (headerName, headerValue) ->
                    header(headerName, headerValue)
                }
            }
            .build()
        var connectedValidated = false
        val sendResult = execution.transport.send(httpRequest, blockingDispatcher) { address ->
            connectedValidated = true
            try {
                execution.policy.validateTarget(execution.target.copy(addresses = listOf(address)))
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                throw HttpPolicyViolation(error.message ?: "outbound HTTP policy rejected connected address")
            }
        }
        if (execution.transport.capability == HttpTransportCapability.CONNECTED_ADDRESS_VALIDATION && !connectedValidated) {
            throw HttpPolicyViolation("connected-address validation was not performed")
        }
        val response = sendResult.response
        val bodyBytes = ByteArrayOutputStream(min(config.maxResponseBytes.toInt(), responseChunkSize))
        var responseSizeBytes = 0L
        var truncated = false
        response.body().use { bodyStream ->
            val buffer = ByteArray(responseChunkSize)
            while (true) {
                val bytesRead = bodyStream.read(buffer)
                if (bytesRead < 0) {
                    break
                }
                responseSizeBytes += bytesRead
                val remaining = config.maxResponseBytes.toInt() - bodyBytes.size()
                if (remaining > 0) {
                    val bytesToWrite = min(bytesRead, remaining)
                    bodyBytes.write(buffer, 0, bytesToWrite)
                    if (bytesToWrite < bytesRead) {
                        truncated = true
                    }
                } else {
                    truncated = true
                }
            }
        }
        if (truncated) {
            observer.onWorkflowEvent(
                workflowName = workflowName,
                name = "tramai.workflow.http.response.truncated",
                attributes = mapOf(
                    "step_name" to name,
                    "http_method" to method,
                    "status_code" to response.statusCode(),
                    "response_size_bytes" to responseSizeBytes,
                    "max_response_bytes" to config.maxResponseBytes,
                ),
                context = context,
            )
        }
        return ExecutedHttpResponse(
            status = response.statusCode(),
            // NOSONAR — java.net.http.HttpHeaders.map() returns mutable Map (Java stdlib limitation)
            headers = response.headers().map().entries.associate { (name, values) ->
                name to values.joinToString(",")
            },
            bodyBytes = bodyBytes.toByteArray(),
            responseSizeBytes = responseSizeBytes,
        )
    }

    private data class HttpRequestExecution(
        val request: HttpRequest,
        val uri: URI,
        val target: OutboundNetworkTarget,
        val method: String,
        val observer: WorkflowObserver,
        val workflowName: String,
        val context: WorkflowContext,
        val transport: HttpTransport,
        val policy: OutboundNetworkPolicy,
        val redactedUrl: String,
    )

    private fun validateMethod(method: String, originalMethod: String) {
        require(method in supportedHttpMethods) {
            "Workflow HTTP step '$name' uses unsupported HTTP method '$originalMethod'"
        }
    }

    private fun canonicalizeRequestUri(url: String): CanonicalHttpRequest {
        val uri = URI.create(url)
        require(uri.userInfo == null) { "Workflow HTTP step '$name' does not allow URL user-info" }
        val host = uri.host?.trim()?.takeIf { it.isNotEmpty() }
            ?: alternativeIpv4HostFromAuthority(uri)
            ?: throw IllegalArgumentException("Workflow HTTP step '$name' requires a URL with a non-empty hostname")
        val normalizedHost = canonicalizeOutboundHost(host)
        return CanonicalHttpRequest(
            uri = uri,
            target = OutboundNetworkTarget(
                scheme = uri.scheme?.lowercase().orEmpty(),
                host = normalizedHost,
                port = uri.port.takeIf { it >= 0 },
                addresses = emptyList(),
                allowedHostnames = config.allowedHosts?.map(::canonicalizeOutboundHost)?.toSet(),
            ),
        )
    }

    /**
     * Java's URI.getHost() returns null for non-canonical IPv4 literals (e.g. `127.1`,
     * `127.0.1`, `0x7f.0.0.1`) even though the authority is a valid reg-name. Recover
     * the literal from the raw authority so the alternative-encoding parsing can run.
     */
    private fun alternativeIpv4HostFromAuthority(uri: URI): String? {
        val authority = uri.rawAuthority ?: return null
        val hostCandidate = authority.substringBefore(':').removeSuffix(".")
        return parseAlternativeIpv4Literal(hostCandidate)?.hostAddress
    }

    private data class CanonicalHttpRequest(val uri: URI, val target: OutboundNetworkTarget)

    private suspend fun failure(
        workflowName: String,
        error: Throwable,
        code: WorkflowStepFailureCode,
        attempt: Int,
        willRetry: Boolean,
        failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver,
    ): RuntimeException {
        val preview = boundedWorkflowDetailPreview(error.message ?: error::class.java.name)
        deliverWorkflowStepFailure(
            observer = failureDiagnosticObserver,
            event = WorkflowStepFailureDiagnosticEvent(
                workflowName, name, WorkflowStepKind.HTTP, code, attempt, willRetry, error,
                preview.text, preview.truncated,
            ),
        )
        return safeWorkflowStepFailure(
            WorkflowStepKind.HTTP, code, fixedWorkflowStepMessage(WorkflowStepKind.HTTP, code), name, attempt,
        )
    }
}

internal class HttpPolicyViolation(message: String) : IllegalArgumentException(message)

private class ExecutedHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val bodyBytes: ByteArray,
    val responseSizeBytes: Long,
) {
    fun toWorkflowResponse(): HttpResponse = HttpResponse(
        status = status,
        headers = headers,
        body = bodyBytes.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8),
    )
}

private val supportedHttpMethods = setOf("GET", "POST", "PUT", "DELETE", "PATCH")
internal val allowedHttpSchemes = setOf("http", "https")
private const val responseChunkSize = 8_192
internal const val localhostHostName = "localhost"

private fun String.safeStripQueryParameters(): String = runCatching {
    stripQueryParameters()
}.getOrElse {
    substringBefore('?')
}

private fun String.stripQueryParameters(): String {
    val uri = URI.create(this)
    return buildString {
        uri.scheme?.let { append(it).append(':') }
        uri.rawAuthority?.let { append("//").append(it) }
        uri.rawPath?.let { append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }.ifEmpty { substringBefore('?') }
}

private fun resolveHostAddresses(host: String): List<InetAddress> {
    parseAlternativeIpv4Literal(host)?.let { return listOf(it) }
    return InetAddress.getAllByName(host).distinctBy { it.hostAddress }
}

internal fun canonicalizeOutboundHost(host: String): String {
    val unbracketed = host.removePrefix("[").removeSuffix("]").removeSuffix(".")
    require(unbracketed.isNotEmpty()) { "outbound hostname must not be empty" }
    val asciiHost = try {
        if (unbracketed.contains(':')) unbracketed else IDN.toASCII(unbracketed, IDN.ALLOW_UNASSIGNED)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("invalid outbound hostname", error)
    }
    return parseAlternativeIpv4Literal(asciiHost)?.hostAddress ?: asciiHost.lowercase()
}

private fun jitteredDelayMillis(attempt: Int): Long =
    ((1_000L shl attempt) * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5)).toLong()

private fun parseAlternativeIpv4Literal(host: String): InetAddress? {
    if (host.contains(':')) {
        return null
    }
    val components = host.split('.')
    if (components.isEmpty() || components.size > 4) {
        return null
    }
    val values = components.map { component -> parseIpv4Component(component) ?: return null }
    val rawAddress = when (values.size) {
        1 -> values.single().takeIf { it in 0L..0xffff_ffff }
        2 -> values[0].takeIf { it in 0L..0xffL }?.let { first ->
            values[1].takeIf { it in 0L..0x00ff_ffffL }?.let { second ->
                (first shl 24) or second
            }
        }
        3 -> values[0].takeIf { it in 0L..0xffL }?.let { first ->
            values[1].takeIf { it in 0L..0xffL }?.let { second ->
                values[2].takeIf { it in 0L..0xffffL }?.let { third ->
                    (first shl 24) or (second shl 16) or third
                }
            }
        }
        4 -> values.takeIf { parts -> parts.all { it in 0L..0xffL } }?.let { parts ->
            (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        }
        else -> null
    } ?: return null
    return InetAddress.getByAddress(
        byteArrayOf(
            ((rawAddress ushr 24) and 0xff).toByte(),
            ((rawAddress ushr 16) and 0xff).toByte(),
            ((rawAddress ushr 8) and 0xff).toByte(),
            (rawAddress and 0xff).toByte(),
        ),
    )
}

private fun parseIpv4Component(component: String): Long? {
    if (component.isEmpty()) {
        return null
    }
    return when {
        component.startsWith("0x", ignoreCase = true) -> component.substring(2).takeIf(String::isNotEmpty)?.toLongOrNull(16)
        component.length > 1 && component.startsWith('0') -> component.toLongOrNull(8)
        else -> component.toLongOrNull()
    }
}
