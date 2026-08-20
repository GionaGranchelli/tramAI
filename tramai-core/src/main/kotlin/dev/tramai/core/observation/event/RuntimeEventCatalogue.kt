package dev.tramai.core.observation.event

/**
 * The authoritative runtime event catalogue (Epic 5.2).
 *
 * Owns every event identifier, every canonical attribute key with its value
 * type, every metric descriptor, and each event's domain, sensitivity,
 * audit/evidence eligibility, telemetry mapping, and declared failure policy.
 * Production code must reference definitions here; literal event/attribute/
 * metric names outside this package are architecture-guarded.
 *
 * Initialisation fails fast on: duplicate event names, duplicate attribute
 * names with incompatible types, required attributes not in the allowed set,
 * and metric mappings that do not exist.
 */
object RuntimeEventCatalogue {
    val allEvents: List<RuntimeEventDefinition> = listOf(
        // ── Worker ─────────────────────────────────────────────────────────
        RuntimeEventDefinition(
            name = "tramai.worker.heartbeat",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_ID,
                RuntimeAttributes.WORKER_UPTIME_MS,
                RuntimeAttributes.WORKER_CLAIMED_COUNT,
            ),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_ID, RuntimeAttributes.WORKER_UPTIME_MS),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKER_HEARTBEATS,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.lease.acquired",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_WORKFLOW_ID,
                RuntimeAttributes.WORKER_ID,
            ),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_WORKFLOW_ID, RuntimeAttributes.WORKER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKER_LEASES,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.lease.released",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_WORKFLOW_ID,
                RuntimeAttributes.WORKER_ID,
            ),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_WORKFLOW_ID, RuntimeAttributes.WORKER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKER_LEASES,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.lease.expired",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_WORKFLOW_ID,
                RuntimeAttributes.WORKER_ID,
            ),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_WORKFLOW_ID, RuntimeAttributes.WORKER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKER_LEASES,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.lease.renewed",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_WORKFLOW_ID,
                RuntimeAttributes.WORKER_ID,
                RuntimeAttributes.LEASE_EXPIRY,
            ),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_WORKFLOW_ID, RuntimeAttributes.WORKER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKER_LEASES,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.lease.contested",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_WORKFLOW_ID,
                RuntimeAttributes.WORKER_ID,
                RuntimeAttributes.WORKER_CURRENT_OWNER,
            ),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_WORKFLOW_ID, RuntimeAttributes.WORKER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKER_LEASES,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.lease.renewal_failed",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_WORKFLOW_ID,
                RuntimeAttributes.WORKER_ID,
                RuntimeAttributes.ERROR_TYPE,
            ),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_WORKFLOW_ID, RuntimeAttributes.WORKER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKER_LEASES,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.lease.release_failed",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_WORKFLOW_ID,
                RuntimeAttributes.WORKER_ID,
                RuntimeAttributes.ERROR_TYPE,
            ),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_WORKFLOW_ID, RuntimeAttributes.WORKER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKER_LEASES,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.poll.failed",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_ID,
                RuntimeAttributes.ERROR_TYPE,
            ),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = false,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.work_taken_over",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_WORKFLOW_ID,
                RuntimeAttributes.WORKER_PREVIOUS_OWNER,
                RuntimeAttributes.WORKER_ID,
            ),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_WORKFLOW_ID, RuntimeAttributes.WORKER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.unknown_attempt",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_RUN_ID,
                RuntimeAttributes.WORKER_STEP_NAME,
                RuntimeAttributes.WORKER_PRIOR_WORKER_ID,
                RuntimeAttributes.WORKER_ATTEMPT_TIME,
            ),
            requiredAttributes = setOf(
                RuntimeAttributes.WORKER_RUN_ID,
                RuntimeAttributes.WORKER_STEP_NAME,
                RuntimeAttributes.WORKER_PRIOR_WORKER_ID,
            ),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.step.started",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_RUN_ID,
                RuntimeAttributes.WORKER_STEP_NAME,
                RuntimeAttributes.WORKER_ATTEMPT_ID,
                RuntimeAttributes.WORKER_ID,
            ),
            requiredAttributes = setOf(
                RuntimeAttributes.WORKER_RUN_ID,
                RuntimeAttributes.WORKER_STEP_NAME,
                RuntimeAttributes.WORKER_ATTEMPT_ID,
                RuntimeAttributes.WORKER_ID,
            ),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.step.completed",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_RUN_ID,
                RuntimeAttributes.WORKER_STEP_NAME,
                RuntimeAttributes.WORKER_ATTEMPT_ID,
                RuntimeAttributes.WORKER_ID,
            ),
            requiredAttributes = setOf(
                RuntimeAttributes.WORKER_RUN_ID,
                RuntimeAttributes.WORKER_STEP_NAME,
                RuntimeAttributes.WORKER_ATTEMPT_ID,
                RuntimeAttributes.WORKER_ID,
            ),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.step.failed",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_RUN_ID,
                RuntimeAttributes.WORKER_STEP_NAME,
                RuntimeAttributes.WORKER_ATTEMPT_ID,
                RuntimeAttributes.WORKER_ID,
                RuntimeAttributes.ERROR_TYPE,
            ),
            requiredAttributes = setOf(
                RuntimeAttributes.WORKER_RUN_ID,
                RuntimeAttributes.WORKER_STEP_NAME,
                RuntimeAttributes.WORKER_ATTEMPT_ID,
                RuntimeAttributes.WORKER_ID,
            ),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.shutdown.started",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(RuntimeAttributes.WORKER_ID),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = false,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.drain.progress",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_ID,
                RuntimeAttributes.WORKER_DRAIN_DONE,
                RuntimeAttributes.WORKER_DRAIN_PENDING,
            ),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.shutdown.complete",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(RuntimeAttributes.WORKER_ID),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKER_SHUTDOWNS,
        ),
        RuntimeEventDefinition(
            name = "tramai.worker.workflow.abandoned",
            domain = RuntimeEventDomain.WORKER,
            allowedAttributes = setOf(
                RuntimeAttributes.WORKER_WORKFLOW_ID,
                RuntimeAttributes.WORKER_ID,
                RuntimeAttributes.WORKER_LAST_STEP,
                RuntimeAttributes.WORKER_TIMEOUT_MS,
            ),
            requiredAttributes = setOf(RuntimeAttributes.WORKER_WORKFLOW_ID, RuntimeAttributes.WORKER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
        ),
        // ── Workflow ───────────────────────────────────────────────────────
        RuntimeEventDefinition(
            name = "tramai.workflow.step.started",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
        ),
        RuntimeEventDefinition(
            name = "tramai.workflow.step.completed",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
        ),
        RuntimeEventDefinition(
            name = "tramai.workflow.step.failed",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(
                RuntimeAttributes.STEP_NAME,
                RuntimeAttributes.ERROR_TYPE,
            ),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
        ),
        // ── Engine / routing / tool ────────────────────────────────────────
        RuntimeEventDefinition(
            name = "tramai.route.selected",
            domain = RuntimeEventDomain.ROUTING,
            allowedAttributes = setOf(
                RuntimeAttributes.PROVIDER_ID,
                RuntimeAttributes.EFFECTIVE_MODEL,
                RuntimeAttributes.ROUTE_INDEX,
                RuntimeAttributes.IS_FALLBACK,
            ),
            requiredAttributes = setOf(RuntimeAttributes.PROVIDER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.ENGINE_EVENTS,
        ),
        RuntimeEventDefinition(
            name = "tramai.circuit.opened",
            domain = RuntimeEventDomain.ROUTING,
            allowedAttributes = setOf(RuntimeAttributes.PROVIDER_ID),
            requiredAttributes = setOf(RuntimeAttributes.PROVIDER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.ENGINE_EVENTS,
        ),
        RuntimeEventDefinition(
            name = "tramai.streaming.startup_retry",
            domain = RuntimeEventDomain.ENGINE,
            allowedAttributes = setOf(
                RuntimeAttributes.PROVIDER_ID,
                RuntimeAttributes.FAILURE_TYPE,
            ),
            requiredAttributes = setOf(RuntimeAttributes.PROVIDER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.ENGINE_EVENTS,
        ),
        RuntimeEventDefinition(
            name = "tramai.retry.scheduled",
            domain = RuntimeEventDomain.ENGINE,
            allowedAttributes = setOf(
                RuntimeAttributes.PROVIDER_ID,
                RuntimeAttributes.RETRY_INDEX,
                RuntimeAttributes.DELAY_MILLIS,
                RuntimeAttributes.DELAY_SOURCE,
            ),
            requiredAttributes = setOf(RuntimeAttributes.PROVIDER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.ENGINE_EVENTS,
        ),
        RuntimeEventDefinition(
            name = "tramai.dlp.inspection_failed",
            domain = RuntimeEventDomain.POLICY,
            allowedAttributes = setOf(
                RuntimeAttributes.PROVIDER_ID_FULL,
                RuntimeAttributes.TOOL_NAME,
                RuntimeAttributes.CORRELATION_ID,
            ),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.ENGINE_EVENTS,
        ),
        RuntimeEventDefinition(
            name = "tramai.dlp.tool_result_rejected",
            domain = RuntimeEventDomain.TOOL,
            allowedAttributes = setOf(
                RuntimeAttributes.REASON_CODE,
                RuntimeAttributes.AGGREGATE_TEXT_LENGTH,
                RuntimeAttributes.CONFIGURED_LIMIT,
                RuntimeAttributes.CORRELATION_ID,
                RuntimeAttributes.TOOL_NAME,
            ),
            requiredAttributes = setOf(RuntimeAttributes.REASON_CODE),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.DLP_TOOL_REJECTED,
        ),
        RuntimeEventDefinition(
            name = "tramai.approval.authorization_replayed",
            domain = RuntimeEventDomain.APPROVAL,
            allowedAttributes = setOf(RuntimeAttributes.APPROVAL_ID, RuntimeAttributes.WORKFLOW_RUN_ID, RuntimeAttributes.TOOL_NAME),
            requiredAttributes = setOf(RuntimeAttributes.APPROVAL_ID, RuntimeAttributes.WORKFLOW_RUN_ID, RuntimeAttributes.TOOL_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.ENGINE_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.token_budget.usage_unavailable",
            domain = RuntimeEventDomain.ENGINE,
            allowedAttributes = setOf(RuntimeAttributes.PROVIDER_ID, RuntimeAttributes.EFFECTIVE_MODEL),
            requiredAttributes = setOf(RuntimeAttributes.PROVIDER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.ENGINE_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.token_budget.soft_limit_exceeded",
            domain = RuntimeEventDomain.ENGINE,
            allowedAttributes = setOf(RuntimeAttributes.PROVIDER_ID, RuntimeAttributes.EFFECTIVE_MODEL, RuntimeAttributes.LIMIT_TOKENS, RuntimeAttributes.OBSERVED_TOKENS, RuntimeAttributes.SCOPE),
            requiredAttributes = setOf(RuntimeAttributes.PROVIDER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.ENGINE_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.token_budget.hard_limit_exceeded",
            domain = RuntimeEventDomain.ENGINE,
            allowedAttributes = setOf(RuntimeAttributes.PROVIDER_ID, RuntimeAttributes.EFFECTIVE_MODEL, RuntimeAttributes.LIMIT_TOKENS, RuntimeAttributes.OBSERVED_TOKENS, RuntimeAttributes.SCOPE),
            requiredAttributes = setOf(RuntimeAttributes.PROVIDER_ID),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.ENGINE_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.security.step_executed",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.STEP_TYPE, RuntimeAttributes.SANITIZER_ACTIVE, RuntimeAttributes.VALIDATOR_ACTIVE, RuntimeAttributes.INSTRUCTION_DEFENSE_ACTIVE, RuntimeAttributes.DEFENSE_MODE),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.security.output_rejected",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.REASON, RuntimeAttributes.RULE_ID),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.security.sanitizer_triggered",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.ORIGINAL_SIZE_BYTES, RuntimeAttributes.MODIFIED_SIZE_BYTES, RuntimeAttributes.RULE_ID),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.security.command_denied",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.COMMAND_DIGEST, RuntimeAttributes.POLICY_TYPE, RuntimeAttributes.STEP_FAMILY),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = true,
            evidenceEligible = true,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.delay.started",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.WORKFLOW_ID_BARE, RuntimeAttributes.RESUME_AT_EPOCH_MILLIS),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.delay.waiting",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.WORKFLOW_ID_BARE, RuntimeAttributes.RESUME_AT_EPOCH_MILLIS),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.delay.resumed",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.WORKFLOW_ID_BARE, RuntimeAttributes.RESUME_AT_EPOCH_MILLIS),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.http.request.validation.failed",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.FAILURE_CODE),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.http.request.policy.rejected",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.FAILURE_CODE),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.http.request.started",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.HTTP_METHOD),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.http.request.completed",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.HTTP_METHOD, RuntimeAttributes.STATUS_CODE, RuntimeAttributes.RESPONSE_SIZE_BYTES),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.http.request.retrying",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.HTTP_METHOD, RuntimeAttributes.STATUS_CODE, RuntimeAttributes.RETRY_ATTEMPT_HTTP, RuntimeAttributes.NEXT_DELAY_MS),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.http.response.truncated",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.HTTP_METHOD, RuntimeAttributes.STATUS_CODE, RuntimeAttributes.RESPONSE_SIZE_BYTES, RuntimeAttributes.MAX_RESPONSE_BYTES),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.mcp.started",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.TOOL_NAME_DIGEST),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.mcp.completed",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.TOOL_NAME_DIGEST, RuntimeAttributes.IS_ERROR, RuntimeAttributes.CONTENT_SIZE_BYTES),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.mcp.reconnecting",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.ATTEMPT, RuntimeAttributes.FAILURE_CODE),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.codex.started",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.AGENT_TYPE, RuntimeAttributes.PROMPT_LENGTH),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.codex.completed",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.AGENT_TYPE, RuntimeAttributes.PROMPT_LENGTH),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.hermes.started",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.AGENT_TYPE, RuntimeAttributes.PROMPT_LENGTH),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.hermes.completed",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.AGENT_TYPE, RuntimeAttributes.PROMPT_LENGTH),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.shell.started",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.shell.completed",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.EXIT_CODE, RuntimeAttributes.STDOUT_BYTES, RuntimeAttributes.STDERR_BYTES),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.shell.timeout",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.shell.truncated",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.STEP_NAME, RuntimeAttributes.STREAM, RuntimeAttributes.ACTUAL_SIZE, RuntimeAttributes.MAX_SIZE),
            requiredAttributes = setOf(RuntimeAttributes.STEP_NAME),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.checkpoint.saved",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE, RuntimeAttributes.NEXT_STEP_INDEX, RuntimeAttributes.STEP_EXECUTIONS, RuntimeAttributes.REVISION, RuntimeAttributes.HAS_LAST_COMPLETED_STEP, RuntimeAttributes.DEFINITION_VERSION, RuntimeAttributes.DEFINITION_DIGEST_ALGORITHM),
            requiredAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.checkpoint.loaded",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE, RuntimeAttributes.NEXT_STEP_INDEX, RuntimeAttributes.STEP_EXECUTIONS, RuntimeAttributes.REVISION, RuntimeAttributes.HAS_LAST_COMPLETED_STEP, RuntimeAttributes.DEFINITION_VERSION, RuntimeAttributes.DEFINITION_DIGEST_ALGORITHM),
            requiredAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.suspended",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE),
            requiredAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.lease.claimed",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE, RuntimeAttributes.LEASE_ID, RuntimeAttributes.OWNER_ID, RuntimeAttributes.CHECKPOINT_REVISION, RuntimeAttributes.ACQUIRED_AT_EPOCH_MILLIS, RuntimeAttributes.EXPIRES_AT_EPOCH_MILLIS),
            requiredAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.lease.renewed",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE, RuntimeAttributes.LEASE_ID, RuntimeAttributes.OWNER_ID, RuntimeAttributes.CHECKPOINT_REVISION, RuntimeAttributes.ACQUIRED_AT_EPOCH_MILLIS, RuntimeAttributes.EXPIRES_AT_EPOCH_MILLIS),
            requiredAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.lease.released",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE, RuntimeAttributes.LEASE_ID, RuntimeAttributes.OWNER_ID, RuntimeAttributes.CHECKPOINT_REVISION, RuntimeAttributes.ACQUIRED_AT_EPOCH_MILLIS, RuntimeAttributes.EXPIRES_AT_EPOCH_MILLIS),
            requiredAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.workflow.lease.conflict",
            domain = RuntimeEventDomain.WORKFLOW,
            allowedAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE, RuntimeAttributes.OWNER_ID, RuntimeAttributes.CHECKPOINT_REVISION, RuntimeAttributes.ERROR_TYPE),
            requiredAttributes = setOf(RuntimeAttributes.WORKFLOW_ID_BARE),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.WORKFLOW_EVENTS,
),
        RuntimeEventDefinition(
            name = "tramai.parse.failure",
            domain = RuntimeEventDomain.ENGINE,
            allowedAttributes = setOf(
                RuntimeAttributes.STRUCTURED_FAILURE_CODE,
                RuntimeAttributes.STRUCTURED_PARSE_SUCCESS,
            ),
            requiredAttributes = setOf(
                RuntimeAttributes.STRUCTURED_FAILURE_CODE,
                RuntimeAttributes.STRUCTURED_PARSE_SUCCESS,
            ),
            sensitivity = RuntimeEventSensitivity.INTERNAL,
            auditEligible = false,
            evidenceEligible = false,
            metricMapping = RuntimeMetrics.OPERATION_PARSE_FAILURES,
        ),
    )

    val byName: Map<String, RuntimeEventDefinition> = allEvents.associateBy { it.name }
    val allAttributes: Map<String, RuntimeAttributeKey<*>> = allEvents
        .flatMap { it.allowedAttributes }
        .distinctBy { it.name }
        .associateBy { it.name }

    init {
        // Duplicate event names.
        val duplicateNames = allEvents.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicateNames.isEmpty()) { "Runtime event catalogue has duplicate event names: $duplicateNames" }

        // Duplicate attribute names with incompatible types.
        val attributesByName = allEvents
            .flatMap { it.allowedAttributes }
            .groupBy { it.name }
        val conflictingTypes = attributesByName.filterValues { keys -> keys.map { it::class }.distinct().size > 1 }.keys
        require(conflictingTypes.isEmpty()) {
            "Runtime event catalogue has duplicate attribute names with different key types: $conflictingTypes"
        }

        // Required attributes must be permitted.
        val unpermittedRequired = allEvents.flatMap { event ->
            event.requiredAttributes.filter { it !in event.allowedAttributes }.map { "${event.name}:${it.name}" }
        }
        require(unpermittedRequired.isEmpty()) {
            "Runtime event catalogue has required attributes outside the allowed set: $unpermittedRequired"
        }

        // Metric mappings must exist.
        val knownMetrics = RuntimeMetrics.all.map { it.name }.toSet()
        val unknownMetrics = allEvents.mapNotNull { it.metricMapping }.map { it.name }.filterNot { it in knownMetrics }
        require(unknownMetrics.isEmpty()) {
            "Runtime event catalogue references undeclared metrics: $unknownMetrics"
        }

        // Metric names must be unique.
        val duplicateMetrics = RuntimeMetrics.all.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicateMetrics.isEmpty()) { "Runtime event catalogue has duplicate metric names: $duplicateMetrics" }
    }

    fun event(name: String): RuntimeEventDefinition =
        byName[name] ?: error("Unknown runtime event '$name'; register it in the catalogue before emitting")
}

/**
 * Explicitly declared dynamic attribute namespace. Intentional extension
 * attributes (e.g. user-supplied workflow context) live under a declared
 * prefix instead of being accidental arbitrary runtime-event attributes.
 */
data class DynamicAttributeNamespace(
    val prefix: String,
    val sensitivity: RuntimeEventSensitivity = RuntimeEventSensitivity.INTERNAL,
) {
    fun key(suffix: String): String = "$prefix$suffix"

    fun matches(name: String): Boolean = name.startsWith(prefix)
}

object DynamicAttributeNamespaces {
    /** User-supplied workflow context attributes: tramai.workflow.context.<key>. */
    val WORKFLOW_CONTEXT = DynamicAttributeNamespace("tramai.workflow.context.")
}

/**
 * Catalogue-owned event-name prefixes for dynamically composed step events
 * (e.g. AgentCliSupport composes "$eventPrefix.started"). The prefix literal
 * lives here so composed names stay under the catalogue's ownership.
 */
object RuntimeEventPrefixes {
    // Deliberately NOT const: a const val would inline the literal into every
    // consumer, defeating the literal-ownership architecture test.
    val CODEX = "tramai.workflow.codex"
    val HERMES = "tramai.workflow.hermes"
}
