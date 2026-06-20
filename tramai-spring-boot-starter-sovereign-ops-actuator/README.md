# TramAI Sovereign Ops Actuator

Optional Spring Boot Actuator integration for sovereign ops worker status.

This module exposes a read-only Actuator endpoint that returns the sanitized
audit outbox worker status snapshot from the sovereign ops starter.

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

## Endpoint

- **ID:** `tramaiSovereignOpsWorker`
- **URL:** `/actuator/tramaiSovereignOpsWorker`
- **Method:** GET
- **Response:** `SovereignOpsAuditOutboxWorkerStatusSnapshot`

## Security

This endpoint returns only sanitized operational state. Applications that
expose the endpoint over HTTP should add their own authentication and
authorization layer (e.g., Spring Security).
