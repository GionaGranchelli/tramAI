# TramAI Sovereign Ops Actuator

Optional Spring Boot Actuator integration for sovereign ops worker status and health.

This module exposes a read-only Actuator endpoint that returns the sanitized
audit outbox worker status snapshot from the sovereign ops starter. It can
also expose an optional Spring Boot health component for the same worker.

It does NOT expose:
- Raw outbox records or outbox IDs
- Approval IDs, reason text, tokens, or replay envelopes
- Prompts, model responses, or tool arguments
- Exception messages, file paths, or stack traces

## Enable

Add the dependency:

```xml
<dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-spring-boot-starter-sovereign-ops-actuator</artifactId>
</dependency>
```

Enable the endpoint in your application:

```yaml
tramai:
  sovereign:
    ops:
      actuator:
        worker-status:
          enabled: true

management:
  endpoints:
    web:
      exposure:
        include: tramaiSovereignOpsWorker
```

The TramAI property creates the endpoint bean. Spring Boot Actuator exposure
must be configured separately -- the endpoint is disabled by default.

## Optional worker health indicator

The module can also create a sanitized Spring Boot `HealthIndicator` for the
audit outbox worker:

```yaml
tramai:
  sovereign:
    ops:
      actuator:
        worker-health:
          enabled: true
```

The health indicator is disabled by default and is independent from the
`worker-status` endpoint property. Enabling `worker-health` does not create
the custom status endpoint, and enabling `worker-status` does not create the
health indicator.

The health details contain only flat operational fields and booleans for
nested state. They do not expose raw records, approval IDs, outbox IDs,
tokens, replay envelopes, prompts, model responses, tool arguments, file
paths, stack traces, exception messages, or reason text.

## Endpoint

- **ID:** `tramaiSovereignOpsWorker`
- **URL:** `/actuator/tramaiSovereignOpsWorker`
- **Method:** GET
- **Response:** `SovereignOpsAuditOutboxWorkerStatusSnapshot`

## Health component

- **Bean name:** `tramaiSovereignOpsWorkerHealthIndicator`
- **Health component name:** derived by Spring Boot from the bean name
- **Status mapping:** disabled worker is `UNKNOWN`; enabled but not running is
  `DOWN`; running with failures before the first success is `DOWN`; running
  after at least one completed cycle is `UP`.

## Security

This endpoint returns only sanitized operational state. Applications that
expose the endpoint over HTTP should add their own authentication and
authorization layer (e.g., Spring Security).

## See also

- [Worker observability runbook](../../docs/operations/sovereign-ops-worker-observability-runbook.md) —
  operator-facing documentation covering the Actuator endpoint, Actuator
  health component, Micrometer metrics, OpenTelemetry metrics, PromQL queries, and
  troubleshooting flows.
