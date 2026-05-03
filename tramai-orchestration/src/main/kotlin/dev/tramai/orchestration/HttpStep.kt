package dev.tramai.orchestration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
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
) {
    init {
        require(timeoutSeconds > 0) { "HttpStepConfig.timeoutSeconds must be greater than zero" }
        require(maxResponseBytes >= 0) { "HttpStepConfig.maxResponseBytes must be zero or greater" }
        require(maxRetries >= 0) { "HttpStepConfig.maxRetries must be zero or greater" }
    }
}

internal object WorkflowHttpClients {
    val default: HttpClient = HttpClient.newHttpClient()
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
        val request = requestBuilder(state, context)
        val method = request.method.trim().uppercase()
        require(method in supportedHttpMethods) {
            "Workflow HTTP step '$name' uses unsupported HTTP method '${request.method}'"
        }

        val redactedUrl = request.url.stripQueryParameters()
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

        var attempt = 0
        while (true) {
            val response = executeRequest(
                request = request,
                method = method,
                observer = observer,
                workflowName = workflowName,
                context = context,
                httpClient = httpClient,
                redactedUrl = redactedUrl,
            )
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
            if (response.status in config.retryOnStatus && attempt < config.maxRetries) {
                val nextDelayMillis = 1_000L shl attempt
                observer.onWorkflowEvent(
                    workflowName = workflowName,
                    name = "tramai.workflow.http.request.retrying",
                    attributes = mapOf(
                        "step_name" to name,
                        "http_method" to method,
                        "url" to redactedUrl,
                        "status_code" to response.status,
                        "retry_attempt" to (attempt + 1),
                        "next_delay_ms" to nextDelayMillis,
                    ),
                    context = context,
                )
                delay(nextDelayMillis)
                attempt += 1
                continue
            }
            return merge(state, response.toWorkflowResponse(), context)
        }
    }

    private suspend fun executeRequest(
        request: HttpRequest,
        method: String,
        observer: WorkflowObserver,
        workflowName: String,
        context: WorkflowContext,
        httpClient: HttpClient,
        redactedUrl: String,
    ): ExecutedHttpResponse {
        val bodyPublisher = request.body?.let(BodyPublishers::ofString) ?: BodyPublishers.noBody()
        val httpRequest = java.net.http.HttpRequest.newBuilder(URI.create(request.url))
            .timeout(Duration.ofSeconds(config.timeoutSeconds))
            .method(method, bodyPublisher)
            .apply {
                request.headers.forEach { (headerName, headerValue) ->
                    header(headerName, headerValue)
                }
            }
            .build()
        val response = withContext(Dispatchers.IO) {
            httpClient.send(httpRequest, BodyHandlers.ofByteArray())
        }
        val responseBytes = response.body()
        val responseSizeBytes = responseBytes.size.toLong()
        val bodyBytes = if (responseSizeBytes > config.maxResponseBytes) {
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
            responseBytes.copyOf(min(config.maxResponseBytes, Int.MAX_VALUE.toLong()).toInt())
        } else {
            responseBytes
        }
        return ExecutedHttpResponse(
            status = response.statusCode(),
            headers = response.headers().map().mapValues { (_, values) -> values.joinToString(",") },
            bodyBytes = bodyBytes,
            responseSizeBytes = responseSizeBytes,
        )
    }
}

private data class ExecutedHttpResponse(
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

private fun String.stripQueryParameters(): String {
    val uri = URI.create(this)
    return buildString {
        uri.scheme?.let { append(it).append(':') }
        uri.rawAuthority?.let { append("//").append(it) }
        uri.rawPath?.let { append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }.ifEmpty { substringBefore('?') }
}
