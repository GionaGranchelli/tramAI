package dev.tramai.core.observation.event

/**
 * Canonical runtime attribute keys. One name, one canonical value type —
 * enforced by [RuntimeEventCatalogue] at initialisation and by the validator
 * at event construction.
 */
object RuntimeAttributes {
    // Worker
    val WORKER_ID = RuntimeAttributeKey<String>("tramai.worker.id", String::class)
    val WORKER_WORKFLOW_ID = RuntimeAttributeKey<String>("tramai.worker.workflow_id", String::class)
    val WORKER_UPTIME_MS = RuntimeAttributeKey<Long>("tramai.worker.uptime_ms", Long::class)
    val WORKER_RUN_ID = RuntimeAttributeKey<String>("tramai.worker.run_id", String::class)
    val WORKER_STEP_NAME = RuntimeAttributeKey<String>("tramai.worker.step_name", String::class)
    val WORKER_ATTEMPT_ID = RuntimeAttributeKey<String>("tramai.worker.attempt_id", String::class)
    val WORKER_CLAIMED_COUNT = RuntimeAttributeKey<Long>("tramai.worker.claimed_count", Long::class)
    val WORKER_CURRENT_OWNER = RuntimeAttributeKey<String>("tramai.worker.current_owner", String::class)
    val WORKER_PRIOR_WORKER_ID = RuntimeAttributeKey<String>("tramai.worker.prior_worker_id", String::class)
    val WORKER_PREVIOUS_OWNER = RuntimeAttributeKey<String>("tramai.worker.previous_owner", String::class)
    val WORKER_ATTEMPT_TIME = RuntimeAttributeKey<Long>("tramai.worker.attempt_time", Long::class)
    val WORKER_DRAIN_DONE = RuntimeAttributeKey<Long>("tramai.worker.drain_done", Long::class)
    val WORKER_DRAIN_PENDING = RuntimeAttributeKey<Long>("tramai.worker.drain_pending", Long::class)
    val WORKER_SHUTDOWN_OUTCOME = RuntimeAttributeKey<String>("tramai.worker.shutdown.outcome", String::class)
    val WORKER_LAST_STEP = RuntimeAttributeKey<String>("tramai.worker.last_step", String::class)
    val WORKER_TIMEOUT_MS = RuntimeAttributeKey<Long>("tramai.worker.timeout_ms", Long::class)
    val WORKER_OUTCOME = RuntimeAttributeKey<String>("tramai.worker.outcome", String::class)
    val WORKER_ABANDONED_WORKFLOW = RuntimeAttributeKey<String>("tramai.worker.abandoned_workflow", String::class)

    // Lease
    val LEASE_OPERATION = RuntimeAttributeKey<String>("tramai.lease.operation", String::class)
    val LEASE_EXPIRY = RuntimeAttributeKey<Long>("tramai.lease.expiry", Long::class)

    // Workflow
    val WORKFLOW_NAME = RuntimeAttributeKey<String>("tramai.workflow.name", String::class)
    val WORKFLOW_ID = RuntimeAttributeKey<String>("tramai.workflow.id", String::class)
    val WORKFLOW_OUTCOME = RuntimeAttributeKey<String>("tramai.workflow.outcome", String::class)
    val EVENT_NAME = RuntimeAttributeKey<String>("tramai.event.name", String::class)
    val STEP_NAME = RuntimeAttributeKey<String>("step_name", String::class)

    // Error/outcome
    val ERROR_TYPE = RuntimeAttributeKey<String>("error_type", String::class)
    val ERROR_TYPE_FULL = RuntimeAttributeKey<String>("tramai.error.type", String::class)

    // Operation / provider
    val GEN_AI_SYSTEM = RuntimeAttributeKey<String>("gen_ai.system", String::class)
    val GEN_AI_REQUEST_MODEL = RuntimeAttributeKey<String>("gen_ai.request.model", String::class)
    val GEN_AI_RESPONSE_MODEL = RuntimeAttributeKey<String>("gen_ai.response.model", String::class)
    val OPERATION_INTERFACE = RuntimeAttributeKey<String>("tramai.operation.interface", String::class)
    val OPERATION_METHOD = RuntimeAttributeKey<String>("tramai.operation.method", String::class)
    val RETRY_ATTEMPT = RuntimeAttributeKey<Long>("tramai.retry.attempt", Long::class)
    val OUTCOME = RuntimeAttributeKey<String>("tramai.outcome", String::class)
    val GEN_AI_USAGE_INPUT_TOKENS = RuntimeAttributeKey<Long>("gen_ai.usage.input_tokens", Long::class)
    val GEN_AI_USAGE_OUTPUT_TOKENS = RuntimeAttributeKey<Long>("gen_ai.usage.output_tokens", Long::class)

    // Structured output
    val STRUCTURED_FAILURE_CODE = RuntimeAttributeKey<String>("tramai.structured.failure_code", String::class)
    val STRUCTURED_PARSE_SUCCESS = RuntimeAttributeKey<Boolean>("tramai.structured.parse_success", Boolean::class)

    // Engine / routing
    val PROVIDER_ID = RuntimeAttributeKey<String>("provider_id", String::class)
    val PROVIDER_ID_FULL = RuntimeAttributeKey<String>("providerId", String::class)
    val FAILURE_TYPE = RuntimeAttributeKey<String>("failure_type", String::class)
    val EFFECTIVE_MODEL = RuntimeAttributeKey<String>("effective_model", String::class)
    val ROUTE_INDEX = RuntimeAttributeKey<Long>("route_index", Long::class)
    val IS_FALLBACK = RuntimeAttributeKey<Boolean>("is_fallback", Boolean::class)
    val RETRY_INDEX = RuntimeAttributeKey<Long>("retry_index", Long::class)
    val DELAY_MILLIS = RuntimeAttributeKey<Long>("delay_millis", Long::class)
    val DELAY_SOURCE = RuntimeAttributeKey<String>("delay_source", String::class)

    // DLP
    val TOOL_NAME = RuntimeAttributeKey<String>("toolName", String::class)
    val CORRELATION_ID = RuntimeAttributeKey<String>("correlationId", String::class)
    val REASON_CODE = RuntimeAttributeKey<String>("reasonCode", String::class)
    val AGGREGATE_TEXT_LENGTH = RuntimeAttributeKey<Long>("aggregateTextLength", Long::class)
    val CONFIGURED_LIMIT = RuntimeAttributeKey<Long>("configuredLimit", Long::class)

    // Step execution
    val EXIT_CODE = RuntimeAttributeKey<Long>("exit_code", Long::class)
    val STDOUT_BYTES = RuntimeAttributeKey<Long>("stdout_bytes", Long::class)
    val STDERR_BYTES = RuntimeAttributeKey<Long>("stderr_bytes", Long::class)
    val MAX_SIZE = RuntimeAttributeKey<Long>("max_size", Long::class)
    val STEP_FAMILY = RuntimeAttributeKey<String>("step_family", String::class)
    val IS_ERROR = RuntimeAttributeKey<Boolean>("is_error", Boolean::class)
    val CONTENT_SIZE_BYTES = RuntimeAttributeKey<Long>("content_size_bytes", Long::class)
    val RESPONSE_SIZE_BYTES = RuntimeAttributeKey<Long>("response_size_bytes", Long::class)
    val RETRY_ATTEMPT_HTTP = RuntimeAttributeKey<Long>("retry_attempt", Long::class)
    val NEXT_DELAY_MS = RuntimeAttributeKey<Long>("next_delay_ms", Long::class)
    val MAX_RESPONSE_BYTES = RuntimeAttributeKey<Long>("max_response_bytes", Long::class)
    val STREAM = RuntimeAttributeKey<String>("stream", String::class)
    val ACTUAL_SIZE = RuntimeAttributeKey<Long>("actual_size", Long::class)
    val TOOL_NAME_DIGEST = RuntimeAttributeKey<String>("tool_name_digest", String::class)
    val ATTEMPT = RuntimeAttributeKey<Long>("attempt", Long::class)
    val HTTP_METHOD = RuntimeAttributeKey<String>("http_method", String::class)
    val STATUS_CODE = RuntimeAttributeKey<Long>("status_code", Long::class)
    val FAILURE_CODE = RuntimeAttributeKey<String>("failure_code", String::class)
    val AGENT_TYPE = RuntimeAttributeKey<String>("agent_type", String::class)
    val PROMPT_LENGTH = RuntimeAttributeKey<Long>("prompt_length", Long::class)
    val RESPONSE_LENGTH = RuntimeAttributeKey<Long>("response_length", Long::class)
    val DURATION_MS = RuntimeAttributeKey<Long>("duration_ms", Long::class)
    val COMMAND_DIGEST = RuntimeAttributeKey<String>("command_digest", String::class)
    val POLICY_TYPE = RuntimeAttributeKey<String>("policy_type", String::class)

    // Security
    val STEP_TYPE = RuntimeAttributeKey<String>("step_type", String::class)
    val SANITIZER_ACTIVE = RuntimeAttributeKey<Boolean>("sanitizer_active", Boolean::class)
    val VALIDATOR_ACTIVE = RuntimeAttributeKey<Boolean>("validator_active", Boolean::class)
    val DEFENSE_MODE = RuntimeAttributeKey<String>("defense_mode", String::class)
    val INSTRUCTION_DEFENSE_ACTIVE = RuntimeAttributeKey<Boolean>("instruction_defense_active", Boolean::class)
    val REASON = RuntimeAttributeKey<String>("reason", String::class)
    val ORIGINAL_SIZE_BYTES = RuntimeAttributeKey<Long>("original_size_bytes", Long::class)
    val MODIFIED_SIZE_BYTES = RuntimeAttributeKey<Long>("modified_size_bytes", Long::class)
    val RULE_ID = RuntimeAttributeKey<String>("rule_id", String::class)

    // Checkpoint / lease / runner
    val WORKFLOW_ID_BARE = RuntimeAttributeKey<String>("workflow_id", String::class)
    val NEXT_STEP_INDEX = RuntimeAttributeKey<Long>("next_step_index", Long::class)
    val STEP_EXECUTIONS = RuntimeAttributeKey<Long>("step_executions", Long::class)
    val REVISION = RuntimeAttributeKey<Long>("revision", Long::class)
    val HAS_LAST_COMPLETED_STEP = RuntimeAttributeKey<Boolean>("has_last_completed_step", Boolean::class)
    val LEASE_ID = RuntimeAttributeKey<String>("lease_id", String::class)
    val OWNER_ID = RuntimeAttributeKey<String>("owner_id", String::class)
    val CHECKPOINT_REVISION = RuntimeAttributeKey<Long>("checkpoint_revision", Long::class)
    val ACQUIRED_AT_EPOCH_MILLIS = RuntimeAttributeKey<Long>("acquired_at_epoch_millis", Long::class)
    val EXPIRES_AT_EPOCH_MILLIS = RuntimeAttributeKey<Long>("expires_at_epoch_millis", Long::class)

    // Budget / approval
    val LIMIT_TOKENS = RuntimeAttributeKey<Long>("limit_tokens", Long::class)
    val USED_TOKENS = RuntimeAttributeKey<Long>("used_tokens", Long::class)
    val OBSERVED_TOKENS = RuntimeAttributeKey<Long>("observed_tokens", Long::class)
    val SCOPE = RuntimeAttributeKey<String>("scope", String::class)
    val APPROVAL_ID = RuntimeAttributeKey<String>("approvalId", String::class)
    val WORKFLOW_RUN_ID = RuntimeAttributeKey<String>("workflowRunId", String::class)

    // Definition / delay metadata
    val DEFINITION_VERSION = RuntimeAttributeKey<String>("tramai.workflow.definition.version", String::class)
    val DEFINITION_DIGEST = RuntimeAttributeKey<String>("tramai.workflow.definition.digest", String::class)
    val DEFINITION_DIGEST_ALGORITHM = RuntimeAttributeKey<String>("tramai.workflow.definition.digest.algorithm", String::class)
    val DELAY_STEP = RuntimeAttributeKey<String>("tramai.workflow.delay.step", String::class)
    val RESUME_AT_EPOCH_MILLIS = RuntimeAttributeKey<Long>("resume_at_epoch_millis", Long::class)
    val DELAY_RESUME_AT_EPOCH_MILLIS = RuntimeAttributeKey<Long>("tramai.workflow.delay.resume_at_epoch_millis", Long::class)

    // Scheduler / run-store
    val STEP_ID = RuntimeAttributeKey<String>("step_id", String::class)
    val SCHEDULER_DELAY_STEP_ID = RuntimeAttributeKey<String>("tramai.delay.step_id", String::class)
    val SCHEDULER_DELAY_RESUME_AT = RuntimeAttributeKey<Long>("tramai.delay.resume_at_epoch_millis", Long::class)
    val SCHEDULE_TICK_ID = RuntimeAttributeKey<String>("tramai.schedule.tick_id", String::class)
    val SCHEDULE_SCHEDULE_ID = RuntimeAttributeKey<String>("tramai.schedule.schedule_id", String::class)
    val SCHEDULE_SCHEDULED_FIRE_AT = RuntimeAttributeKey<Long>("tramai.schedule.scheduled_fire_at_epoch_millis", Long::class)

    // Sovereign ops outbox worker (tag keys)
    // Namespaced keys preserve the OpenTelemetry sovereign observer's legacy
    // attribute names; the bare keys preserve the Micrometer observer's legacy
    // tag names. Both contracts are catalogue-driven without renaming.
    val OUTBOX_FAILURE_ACTION = RuntimeAttributeKey<String>("tramai.sovereign.ops.outbox.worker.failure_action", String::class)
    val OUTBOX_ERROR_TYPE = RuntimeAttributeKey<String>("tramai.sovereign.ops.outbox.worker.error_type", String::class)
    val OUTBOX_WORKER_OUTCOME = RuntimeAttributeKey<String>("tramai.sovereign.ops.outbox.worker.outcome", String::class)
    val OUTBOX_RECOVERY_RESULT = RuntimeAttributeKey<String>("tramai.sovereign.ops.outbox.recovery.result", String::class)
    val OUTBOX_DISPATCH_RESULT = RuntimeAttributeKey<String>("tramai.sovereign.ops.outbox.dispatch.result", String::class)

    // Legacy bare names for the Micrometer sovereign observer (unchanged contract).
    val OUTBOX_OUTCOME = RuntimeAttributeKey<String>("outcome", String::class)
    val FAILURE_ACTION = RuntimeAttributeKey<String>("failure_action", String::class)
    val RESULT = RuntimeAttributeKey<String>("result", String::class)
}
