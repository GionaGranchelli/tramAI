# Runtime Event Catalogue

Generated from `RuntimeEventCatalogue`; do not edit by hand. Deterministic and CI-checked.

## Events

| Event | Domain | Required attributes | Sensitivity | Audit | Evidence | Metric | Span | Failure policy |
|---|---|---|---|---|---|---|---|---|
|`tramai.worker.heartbeat`|WORKER|`tramai.worker.id`, `tramai.worker.uptime_ms`|INTERNAL|no|no|`tramai.worker.heartbeats`|yes|FAIL_OPEN|
|`tramai.worker.lease.acquired`|WORKER|`tramai.worker.id`, `tramai.worker.workflow_id`|INTERNAL|yes|yes|`tramai.worker.leases`|yes|FAIL_OPEN|
|`tramai.worker.lease.released`|WORKER|`tramai.worker.id`, `tramai.worker.workflow_id`|INTERNAL|yes|yes|`tramai.worker.leases`|yes|FAIL_OPEN|
|`tramai.worker.lease.expired`|WORKER|`tramai.worker.id`, `tramai.worker.workflow_id`|INTERNAL|yes|yes|`tramai.worker.leases`|yes|FAIL_OPEN|
|`tramai.worker.lease.renewed`|WORKER|`tramai.worker.id`, `tramai.worker.workflow_id`|INTERNAL|yes|yes|`tramai.worker.leases`|yes|FAIL_OPEN|
|`tramai.worker.lease.contested`|WORKER|`tramai.worker.id`, `tramai.worker.workflow_id`|INTERNAL|yes|yes|`tramai.worker.leases`|yes|FAIL_OPEN|
|`tramai.worker.lease.renewal_failed`|WORKER|`tramai.worker.id`, `tramai.worker.workflow_id`|INTERNAL|yes|yes|`tramai.worker.leases`|yes|FAIL_OPEN|
|`tramai.worker.lease.release_failed`|WORKER|`tramai.worker.id`, `tramai.worker.workflow_id`|INTERNAL|yes|yes|`tramai.worker.leases`|yes|FAIL_OPEN|
|`tramai.worker.poll.failed`|WORKER|`tramai.worker.id`|INTERNAL|yes|no|—|yes|FAIL_OPEN|
|`tramai.worker.work_taken_over`|WORKER|`tramai.worker.id`, `tramai.worker.workflow_id`|INTERNAL|yes|yes|—|yes|FAIL_OPEN|
|`tramai.worker.unknown_attempt`|WORKER|`tramai.worker.prior_worker_id`, `tramai.worker.run_id`, `tramai.worker.step_name`|INTERNAL|yes|yes|—|yes|FAIL_OPEN|
|`tramai.worker.step.started`|WORKER|`tramai.worker.attempt_id`, `tramai.worker.id`, `tramai.worker.run_id`, `tramai.worker.step_name`|INTERNAL|yes|yes|—|yes|FAIL_OPEN|
|`tramai.worker.step.completed`|WORKER|`tramai.worker.attempt_id`, `tramai.worker.id`, `tramai.worker.run_id`, `tramai.worker.step_name`|INTERNAL|yes|yes|—|yes|FAIL_OPEN|
|`tramai.worker.step.failed`|WORKER|`tramai.worker.attempt_id`, `tramai.worker.id`, `tramai.worker.run_id`, `tramai.worker.step_name`|INTERNAL|yes|yes|—|yes|FAIL_OPEN|
|`tramai.worker.shutdown.started`|WORKER|`tramai.worker.id`|INTERNAL|yes|no|—|yes|FAIL_OPEN|
|`tramai.worker.drain.progress`|WORKER|`tramai.worker.id`|INTERNAL|no|no|—|yes|FAIL_OPEN|
|`tramai.worker.shutdown.complete`|WORKER|`tramai.worker.id`|INTERNAL|yes|no|`tramai.worker.shutdowns`|yes|FAIL_OPEN|
|`tramai.worker.workflow.abandoned`|WORKER|`tramai.worker.id`, `tramai.worker.workflow_id`|INTERNAL|yes|yes|—|yes|FAIL_OPEN|
|`tramai.workflow.step.started`|WORKFLOW|`step_name`|INTERNAL|yes|yes|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.step.completed`|WORKFLOW|`step_name`|INTERNAL|yes|yes|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.step.failed`|WORKFLOW|`step_name`|INTERNAL|yes|yes|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.route.selected`|ROUTING|`provider_id`|INTERNAL|no|no|`tramai.engine.events`|yes|FAIL_OPEN|
|`tramai.circuit.opened`|ROUTING|`provider_id`|INTERNAL|no|no|`tramai.engine.events`|yes|FAIL_OPEN|
|`tramai.streaming.startup_retry`|ENGINE|`provider_id`|INTERNAL|no|no|`tramai.engine.events`|yes|FAIL_OPEN|
|`tramai.retry.scheduled`|ENGINE|`provider_id`|INTERNAL|no|no|`tramai.engine.events`|yes|FAIL_OPEN|
|`tramai.dlp.inspection_failed`|POLICY||INTERNAL|yes|no|`tramai.engine.events`|yes|FAIL_OPEN|
|`tramai.dlp.tool_result_rejected`|TOOL|`reasonCode`|INTERNAL|yes|no|`tramai.dlp.tool_result_rejected`|yes|FAIL_OPEN|
|`tramai.approval.authorization_replayed`|APPROVAL|`approvalId`, `toolName`, `workflowRunId`|INTERNAL|yes|yes|`tramai.engine.events`|yes|FAIL_OPEN|
|`tramai.token_budget.usage_unavailable`|ENGINE|`provider_id`|INTERNAL|no|no|`tramai.engine.events`|yes|FAIL_OPEN|
|`tramai.token_budget.soft_limit_exceeded`|ENGINE|`provider_id`|INTERNAL|yes|no|`tramai.engine.events`|yes|FAIL_OPEN|
|`tramai.token_budget.hard_limit_exceeded`|ENGINE|`provider_id`|INTERNAL|yes|no|`tramai.engine.events`|yes|FAIL_OPEN|
|`tramai.workflow.security.step_executed`|WORKFLOW|`step_name`|INTERNAL|yes|yes|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.security.output_rejected`|WORKFLOW|`step_name`|INTERNAL|yes|yes|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.security.sanitizer_triggered`|WORKFLOW|`step_name`|INTERNAL|yes|yes|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.security.command_denied`|WORKFLOW|`step_name`|INTERNAL|yes|yes|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.delay.started`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.delay.waiting`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.delay.resumed`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.http.request.validation.failed`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.http.request.policy.rejected`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.http.request.started`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.http.request.completed`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.http.request.retrying`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.http.response.truncated`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.mcp.started`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.mcp.completed`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.mcp.reconnecting`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.codex.started`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.codex.completed`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.hermes.started`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.hermes.completed`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.shell.started`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.shell.completed`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.shell.timeout`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.shell.truncated`|WORKFLOW|`step_name`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.checkpoint.saved`|WORKFLOW|`workflow_id`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.checkpoint.loaded`|WORKFLOW|`workflow_id`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.suspended`|WORKFLOW|`workflow_id`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.lease.claimed`|WORKFLOW|`workflow_id`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.lease.renewed`|WORKFLOW|`workflow_id`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.lease.released`|WORKFLOW|`workflow_id`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.workflow.lease.conflict`|WORKFLOW|`workflow_id`|INTERNAL|no|no|`tramai.workflow.events`|yes|FAIL_OPEN|
|`tramai.scheduler.delay_wakeup.unregistered`|SCHEDULER|`workflow_id`|INTERNAL|yes|no|—|yes|FAIL_OPEN|
|`tramai.workflow.running`|WORKFLOW||INTERNAL|yes|no|—|yes|FAIL_OPEN|
|`tramai.workflow.started`|WORKFLOW||INTERNAL|yes|no|—|yes|FAIL_OPEN|
|`tramai.workflow.completed`|WORKFLOW||INTERNAL|yes|no|—|yes|FAIL_OPEN|
|`tramai.workflow.failed`|WORKFLOW||INTERNAL|yes|no|—|yes|FAIL_OPEN|
|`tramai.workflow.cancelling`|WORKFLOW||INTERNAL|yes|no|—|yes|FAIL_OPEN|
|`tramai.workflow.cancelled`|WORKFLOW||INTERNAL|yes|no|—|yes|FAIL_OPEN|
|`tramai.step.started`|WORKFLOW||INTERNAL|yes|no|—|yes|FAIL_OPEN|
|`tramai.step.completed`|WORKFLOW||INTERNAL|yes|no|—|yes|FAIL_OPEN|
|`tramai.step.failed`|WORKFLOW||INTERNAL|yes|no|—|yes|FAIL_OPEN|
|`tramai.parse.failure`|ENGINE|`tramai.structured.failure_code`, `tramai.structured.parse_success`|INTERNAL|no|no|`tramai.operation.parse_failures`|yes|FAIL_OPEN|

## Metrics

| Metric | Description | Unit | Instrument | Value type |
|---|---|---|---|---|
|`tramai.operation.attempts`|Completed Tramai provider attempts|`{attempt}`|COUNTER|LONG|
|`tramai.operation.duration`|Duration of Tramai provider attempts|`ms`|HISTOGRAM|DOUBLE|
|`tramai.operation.input_tokens`|Total provider input tokens observed by Tramai|`{token}`|COUNTER|LONG|
|`tramai.operation.output_tokens`|Total provider output tokens observed by Tramai|`{token}`|COUNTER|LONG|
|`tramai.operation.input_tokens.per_attempt`|Distribution of input tokens per Tramai provider attempt|`{token}`|HISTOGRAM|LONG|
|`tramai.operation.output_tokens.per_attempt`|Distribution of output tokens per Tramai provider attempt|`{token}`|HISTOGRAM|LONG|
|`tramai.operation.parse_failures`|Structured parse failures observed by Tramai|`{failure}`|COUNTER|LONG|
|`tramai.engine.events`|Engine-owned resilience and routing events emitted by Tramai|`{event}`|COUNTER|LONG|
|`tramai.workflow.runs`|Completed Tramai workflows|`{workflow}`|COUNTER|LONG|
|`tramai.workflow.duration`|Duration of Tramai workflows|`ms`|HISTOGRAM|DOUBLE|
|`tramai.workflow.events`|Workflow-level checkpoint, lease, and step events emitted by Tramai|`{event}`|COUNTER|LONG|
|`tramai.worker.heartbeats`|Worker heartbeat events|`{heartbeat}`|COUNTER|LONG|
|`tramai.worker.shutdowns`|Worker shutdown events|`{shutdown}`|COUNTER|LONG|
|`tramai.worker.leases`|Worker lease operations (acquired, released, expired, renewed, contested, failed)|`{lease}`|COUNTER|LONG|
|`tramai.dlp.tool_result_rejected`|Tool results rejected by DLP inspection|`{rejection}`|COUNTER|LONG|
|`tramai.sovereign.ops.outbox.worker.cycles`|Outbox worker cycles completed per action and outcome|`{cycle}`|COUNTER|LONG|
|`tramai.sovereign.ops.outbox.worker.duration`|Duration of each outbox worker cycle|`ms`|HISTOGRAM|DOUBLE|
|`tramai.sovereign.ops.outbox.worker.failures`|Failure notifications emitted by the sovereign ops audit outbox worker|`{failure}`|COUNTER|LONG|
|`tramai.sovereign.ops.outbox.worker.recovered.records`|Records affected by PREPARED recovery per result type|`{record}`|COUNTER|LONG|
|`tramai.sovereign.ops.outbox.worker.dispatched.records`|Records affected by dispatch per result type|`{record}`|COUNTER|LONG|

## Dynamic attribute namespaces

| Namespace | Prefix | Sensitivity |
|---|---|---|
|workflow context|`tramai.workflow.context.`|INTERNAL|
