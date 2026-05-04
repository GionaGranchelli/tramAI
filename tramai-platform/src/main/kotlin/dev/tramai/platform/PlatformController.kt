package dev.tramai.platform

import dev.tramai.server.WorkflowRunDetail
import dev.tramai.server.WorkflowRunPage
import dev.tramai.server.WorkflowRunResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

data class PluginInstallRequest(
    val jarPath: String,
)

@RestController
class PlatformController(
    private val workflowService: PlatformWorkflowService,
    private val apiKeyService: ApiKeyService,
    private val apiKeyAuthenticator: ApiKeyAuthenticator,
    private val rateLimiter: ApiKeyRateLimiter,
    private val pluginManager: PluginManager,
    private val auditLogService: AuditLogService,
) {
    @PostMapping("/workflows/{name}/run")
    fun runWorkflow(
        @PathVariable name: String,
        @RequestBody body: String,
        @RequestHeader("X-API-Key", required = false) apiKey: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<WorkflowRunResponse> {
        val principal = apiKeyAuthenticator.authenticate(apiKey)
        apiKeyAuthenticator.requireScope(principal, ApiKeyScope.RUN)
        val decision = rateLimiter.check(principal.record)
        if (!decision.allowed) {
            throw RateLimitExceededException(decision)
        }
        val response = workflowService.runWorkflow(
            teamId = principal.teamId,
            projectId = principal.projectId,
            actorId = principal.actorId,
            workflowName = name,
            body = body,
            idempotencyKey = idempotencyKey,
        )
        return ResponseEntity.ok()
            .header("X-RateLimit-Limit", decision.limit.toString())
            .header("X-RateLimit-Remaining", decision.remaining.toString())
            .header("X-RateLimit-Reset", decision.resetAtEpochSeconds.toString())
            .body(response)
    }

    @GetMapping("/workflows/{name}/runs")
    fun listRuns(
        @PathVariable name: String,
        @RequestHeader("X-API-Key", required = false) apiKey: String?,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int,
    ): WorkflowRunPage {
        val principal = apiKeyAuthenticator.authenticate(apiKey)
        apiKeyAuthenticator.requireScope(principal, ApiKeyScope.READ)
        return workflowService.listRuns(
            teamId = principal.teamId,
            projectId = principal.projectId,
            workflowName = name,
            offset = offset,
            limit = limit,
        )
    }

    @GetMapping("/workflows/{name}/runs/{id}")
    fun getRun(
        @PathVariable name: String,
        @PathVariable id: String,
        @RequestHeader("X-API-Key", required = false) apiKey: String?,
    ): WorkflowRunDetail {
        val principal = apiKeyAuthenticator.authenticate(apiKey)
        apiKeyAuthenticator.requireScope(principal, ApiKeyScope.READ)
        return workflowService.getRun(
            teamId = principal.teamId,
            projectId = principal.projectId,
            workflowName = name,
            workflowId = id,
        )
    }

    @PostMapping("/webhooks/{name}")
    fun receiveWebhook(
        @PathVariable name: String,
        @RequestParam teamId: String,
        @RequestParam projectId: String,
        @RequestParam source: String,
        @RequestBody body: String,
        @RequestHeader headers: Map<String, String>,
    ): ResponseEntity<WorkflowRunResponse> = ResponseEntity.accepted().body(
        workflowService.runWebhook(
            teamId = teamId,
            projectId = projectId,
            workflowName = name,
            sourceId = source,
            payload = body,
            headers = headers,
            idempotencyKey = headers["X-GitHub-Delivery"] ?: headers["X-Delivery-ID"],
        ),
    )

    @PostMapping("/api-keys")
    fun createApiKey(
        @RequestBody request: CreateApiKeyRequest,
        @RequestHeader("X-API-Key", required = false) apiKey: String?,
    ): ApiKeyResponse {
        val principal = apiKeyAuthenticator.authenticate(apiKey)
        apiKeyAuthenticator.requireScope(principal, ApiKeyScope.ADMIN)
        require(principal.teamId == request.teamId) {
            "API keys can only be created inside the authenticated team"
        }
        val created = apiKeyService.create(request, actorId = principal.actorId)
        return created.record.toResponse(rawKey = created.key)
    }

    @GetMapping("/api-keys")
    fun listApiKeys(
        @RequestHeader("X-API-Key", required = false) apiKey: String?,
    ): List<ApiKeyResponse> {
        val principal = apiKeyAuthenticator.authenticate(apiKey)
        apiKeyAuthenticator.requireScope(principal, ApiKeyScope.ADMIN)
        return apiKeyService.list(principal.teamId, principal.projectId).map(ApiKeyRecord::toResponse)
    }

    @DeleteMapping("/api-keys/{id}")
    fun revokeApiKey(
        @PathVariable id: String,
        @RequestHeader("X-API-Key", required = false) apiKey: String?,
    ): ApiKeyResponse {
        val principal = apiKeyAuthenticator.authenticate(apiKey)
        apiKeyAuthenticator.requireScope(principal, ApiKeyScope.ADMIN)
        val existing = apiKeyService.get(id) ?: throw IllegalArgumentException("API key '$id' was not found")
        require(existing.teamId == principal.teamId) {
            "API keys can only be revoked inside the authenticated team"
        }
        val revoked = apiKeyService.revoke(id, actorId = principal.actorId)
        return revoked.toResponse()
    }

    @GetMapping("/audit-log")
    fun auditLog(
        @RequestHeader("X-API-Key", required = false) apiKey: String?,
        @RequestParam(required = false) team: String?,
        @RequestParam(required = false) action: String?,
    ): List<AuditLogEntry> {
        val principal = apiKeyAuthenticator.authenticate(apiKey)
        apiKeyAuthenticator.requireScope(principal, ApiKeyScope.ADMIN)
        if (team != null && team != principal.teamId) {
            return emptyList()
        }
        return auditLogService.list(principal.teamId, action)
    }

    @GetMapping("/plugins")
    fun plugins(
        @RequestHeader("X-API-Key", required = false) apiKey: String?,
    ): List<PluginView> {
        val principal = apiKeyAuthenticator.authenticate(apiKey)
        apiKeyAuthenticator.requireScope(principal, ApiKeyScope.ADMIN)
        return pluginManager.list()
    }

    @PostMapping("/plugins/install")
    fun installPlugin(
        @RequestBody request: PluginInstallRequest,
        @RequestHeader("X-API-Key", required = false) apiKey: String?,
    ): List<PluginView> {
        val principal = apiKeyAuthenticator.authenticate(apiKey)
        apiKeyAuthenticator.requireScope(principal, ApiKeyScope.ADMIN)
        return pluginManager.install(request.jarPath)
    }

    @PostMapping("/plugins/{id}/enable")
    fun enablePlugin(
        @PathVariable id: String,
        @RequestHeader("X-API-Key", required = false) apiKey: String?,
    ): PluginView {
        val principal = apiKeyAuthenticator.authenticate(apiKey)
        apiKeyAuthenticator.requireScope(principal, ApiKeyScope.ADMIN)
        return pluginManager.enable(id)
    }

    @PostMapping("/plugins/{id}/disable")
    fun disablePlugin(
        @PathVariable id: String,
        @RequestHeader("X-API-Key", required = false) apiKey: String?,
    ): PluginView {
        val principal = apiKeyAuthenticator.authenticate(apiKey)
        apiKeyAuthenticator.requireScope(principal, ApiKeyScope.ADMIN)
        return pluginManager.disable(id)
    }
}

@RestControllerAdvice
class PlatformErrorHandler {
    private val logger = LoggerFactory.getLogger(PlatformErrorHandler::class.java)

    @ExceptionHandler(
        IllegalArgumentException::class,
        PlatformBadRequestException::class,
    )
    fun badRequest(error: RuntimeException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "Invalid request", error.message ?: "Request is invalid")

    @ExceptionHandler(AuthenticationException::class)
    fun unauthorized(error: AuthenticationException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.UNAUTHORIZED, "Authentication failed", error.message ?: "API key is invalid")

    @ExceptionHandler(AuthorizationException::class)
    fun forbidden(error: AuthorizationException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.FORBIDDEN, "Forbidden", error.message ?: "Operation is not permitted")

    @ExceptionHandler(RateLimitExceededException::class)
    fun rateLimited(error: RateLimitExceededException): ResponseEntity<ProblemDetail> {
        val decision = error.decision
        val body = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, error.message ?: "Rate limit exceeded")
        body.type = URI.create("https://tramai.dev/problems/429")
        body.title = "Rate limit exceeded"
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, decision.retryAfterSeconds.toString())
            .header("X-RateLimit-Limit", decision.limit.toString())
            .header("X-RateLimit-Remaining", decision.remaining.toString())
            .header("X-RateLimit-Reset", decision.resetAtEpochSeconds.toString())
            .body(body)
    }

    @ExceptionHandler(PluginNotFoundException::class)
    fun pluginNotFound(error: PluginNotFoundException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.NOT_FOUND, "Plugin not found", error.message ?: "Plugin was not found")

    @ExceptionHandler(Throwable::class)
    fun unexpected(error: Throwable): ResponseEntity<ProblemDetail> {
        logger.error("Unexpected platform error", error)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Platform request failed",
            "Platform request failed unexpectedly",
        )
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.type = URI.create("https://tramai.dev/problems/${status.value()}")
        problem.title = title
        return ResponseEntity.status(status).body(problem)
    }
}
