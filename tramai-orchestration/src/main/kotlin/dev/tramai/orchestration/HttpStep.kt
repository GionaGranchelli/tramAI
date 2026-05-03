package dev.tramai.orchestration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
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

class WorkflowHttpException(
    val stepName: String,
    val redactedUrl: String,
    val attempt: Int,
    cause: Throwable,
) : RuntimeException(
    "Workflow HTTP step '$stepName' failed for '$redactedUrl' on attempt $attempt: ${cause.message ?: cause::class.java.simpleName}",
    cause,
)

internal object WorkflowHttpClients {
    val default: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
}

internal data class HttpWorkflowStep<S>(
    override val name: String,
    val requestBuilder: suspend (S, WorkflowContext) -> HttpRequest,
    val merge: suspend (S, HttpResponse, WorkflowContext) -> S,
    val config: HttpStepConfig = HttpStepConfig(),
) : InternalWorkflowStep<S> {
    suspend fun execute(
        workflowName: String,
        state: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
        httpClient: HttpClient,
    ): S {
        val request = try {
            requestBuilder(state, context)
        } catch (error: Throwable) {
            throw wrapHttpError(
                error = error,
                redactedUrl = "<request-builder>",
                attempt = 1,
            )
        }
        val method = request.method.trim().uppercase()
        val redactedUrl = request.url.safeStripQueryParameters()
        val uri = try {
            validateMethod(method, request.method)
            validateRequestUri(request.url)
        } catch (error: Throwable) {
            observer.onWorkflowEvent(
                workflowName = workflowName,
                name = "tramai.workflow.http.request.validation.failed",
                attributes = mapOf(
                    "step_name" to name,
                    "http_method" to method,
                    "url" to redactedUrl,
                    "reason" to (error.message ?: error::class.java.simpleName),
                ),
                context = context,
            )
            throw wrapHttpError(
                error = error,
                redactedUrl = redactedUrl,
                attempt = 1,
            )
        }

        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.http.request.started",
            attributes = mapOf(
                "step_name" to name,
                "http_method" to method,
                "url" to redactedUrl,
            ),
            context = context,
        )

        var retryAttempt = 0
        while (true) {
            val attemptNumber = retryAttempt + 1
            val response = try {
                executeRequest(
                    request = request,
                    uri = uri,
                    method = method,
                    observer = observer,
                    workflowName = workflowName,
                    context = context,
                    httpClient = httpClient,
                    redactedUrl = redactedUrl,
                )
            } catch (error: Throwable) {
                throw wrapHttpError(
                    error = error,
                    redactedUrl = redactedUrl,
                    attempt = attemptNumber,
                )
            }

            observer.onWorkflowEvent(
                workflowName = workflowName,
                name = "tramai.workflow.http.request.completed",
                attributes = mapOf(
                    "step_name" to name,
                    "http_method" to method,
                    "url" to redactedUrl,
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
                        "url" to redactedUrl,
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
                throw wrapHttpError(
                    error = error,
                    redactedUrl = redactedUrl,
                    attempt = attemptNumber,
                )
            }
        }
    }

    private suspend fun executeRequest(
        request: HttpRequest,
        uri: URI,
        method: String,
        observer: WorkflowObserver,
        workflowName: String,
        context: WorkflowContext,
        httpClient: HttpClient,
        redactedUrl: String,
    ): ExecutedHttpResponse {
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
        val response = withContext(Dispatchers.IO) {
            httpClient.send(httpRequest, BodyHandlers.ofInputStream())
        }
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
                    "url" to redactedUrl,
                    "status_code" to response.statusCode(),
                    "response_size_bytes" to responseSizeBytes,
                    "max_response_bytes" to config.maxResponseBytes,
                ),
                context = context,
            )
        }
        return ExecutedHttpResponse(
            status = response.statusCode(),
            headers = response.headers().map().mapValues { (_, values) -> values.joinToString(",") },
            bodyBytes = bodyBytes.toByteArray(),
            responseSizeBytes = responseSizeBytes,
        )
    }

    private fun validateMethod(method: String, originalMethod: String) {
        require(method in supportedHttpMethods) {
            "Workflow HTTP step '$name' uses unsupported HTTP method '$originalMethod'"
        }
    }

    private fun validateRequestUri(url: String): URI {
        val uri = URI.create(url)
        val scheme = uri.scheme?.lowercase()
        require(scheme in allowedHttpSchemes) {
            "Workflow HTTP step '$name' only supports http and https URLs"
        }
        val host = uri.host?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Workflow HTTP step '$name' requires a URL with a non-empty hostname")
        val normalizedHost = host.lowercase()
        val allowedHosts = config.allowedHosts?.map { it.lowercase() }?.toSet()
        when {
            allowedHosts != null -> require(normalizedHost in allowedHosts) {
                "Workflow HTTP step '$name' host '$host' is not in the allowlist"
            }

            normalizedHost == localhostHostName || isPrivateOrLinkLocalIpLiteral(normalizedHost) -> {
                throw IllegalArgumentException(
                    "Workflow HTTP step '$name' host '$host' is not a public address; configure allowedHosts to permit it",
                )
            }
        }
        return uri
    }

    private fun wrapHttpError(
        error: Throwable,
        redactedUrl: String,
        attempt: Int,
    ): WorkflowHttpException = when (error) {
        is WorkflowHttpException -> error
        else -> WorkflowHttpException(
            stepName = name,
            redactedUrl = redactedUrl,
            attempt = attempt,
            cause = error,
        )
    }
}

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
private val allowedHttpSchemes = setOf("http", "https")
private const val responseChunkSize = 8_192
private const val localhostHostName = "localhost"

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

private fun isPrivateOrLinkLocalIpLiteral(host: String): Boolean {
    val address = host.toInetAddressLiteralOrNull() ?: return false
    return address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isCarrierGradeNatIpv4() ||
        address.isUniqueLocalIpv6()
}

private fun String.toInetAddressLiteralOrNull(): InetAddress? = when {
    isIpv4Literal() || isIpv6Literal() -> InetAddress.getByName(this)
    else -> null
}

private fun String.isIpv4Literal(): Boolean {
    val parts = split('.')
    if (parts.size != 4) {
        return false
    }
    return parts.all { part ->
        part.isNotEmpty() &&
            part.length <= 3 &&
            part.all(Char::isDigit) &&
            part.toIntOrNull()?.let { value -> value in 0..255 } == true
    }
}

private fun String.isIpv6Literal(): Boolean = contains(':') && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '%' }

private fun jitteredDelayMillis(attempt: Int): Long =
    ((1_000L shl attempt) * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5)).toLong()

private fun InetAddress.isCarrierGradeNatIpv4(): Boolean {
    if (this !is Inet4Address) {
        return false
    }
    val bytes = address
    return bytes[0].toInt() and 0xff == 100 &&
        (bytes[1].toInt() and 0b1100_0000) == 0b0100_0000
}

private fun InetAddress.isUniqueLocalIpv6(): Boolean {
    if (this !is Inet6Address) {
        return false
    }
    return address[0].toInt() and 0xfe == 0xfc
}
