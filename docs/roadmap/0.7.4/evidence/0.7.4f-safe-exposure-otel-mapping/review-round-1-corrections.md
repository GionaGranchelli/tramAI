
## 7. Review round 1 - corrections

Reviewed head: `3932ad05`. Every finding below was reproduced at that head before editing; none is accepted on
the review's word alone, and none is closed by weakening a gate.

### P1 - a consumer could fabricate authoritative telemetry

Confirmed. `GovernanceDecisionExposure` was a public data class with public `kind`, `authority`, `freshness` and
`recordCompleteness`, and `GovernanceTelemetryExporter.export` accepted any `GovernanceExposure` built by hand. A
caller could declare a `POLICY_DECISION` with `AUTHORITATIVE_PERSISTED` and `COMPLETE` and export it without ever
querying a projection.

Two corrections, because the first was not sufficient:

1. The public types became **interfaces**, and their only implementations are **private to the exposure file**.
   A private constructor was not enough: Kotlin generated a public *synthetic* constructor
   (`...DefaultConstructorMarker`), which the new adversarial test caught, so the class shape could not carry the
   guarantee at the JVM level. An interface has no constructor at all, and it also removes the `copy()` a data
   class would generate.
2. `GovernanceExposure.from` is the only public way to obtain an instance. The module-internal factory is
   `internal` and therefore name-mangled.

The guarantee is asserted, not described: `an exposure cannot be fabricated without a query result` checks that
both public types are interfaces, that `java.constructors` is empty on each, that no `copy` exists, that the
implementation class is not public, and that the companion exposes `from` but not the mangled `of`.

The exporter test that previously constructed an authoritative policy decision now goes through
`GovernanceExposure.from(...)` over a real query result for the XR1 case; the remaining unit fixtures use the
module-internal factory, which no consumer can reach.

### P1 - the emitted events bypassed the catalogue

Confirmed. The catalogue requires `GOVERNANCE_EXPOSURE_STATE` on this event, and decision events were emitted
without it; the exporter called `span.addEvent(...)` directly, so nothing validated the payload.

Every event is now built as a `RuntimeEvent` through `RuntimeEvent.of`, which rejects attributes the catalogue
does not allow for the event, attributes of the wrong canonical type, and events missing a required attribute.
The state is on **every** event, decision events included. Attribute names come from the catalogue's declaration
order, so a key the catalogue does not declare cannot appear and ordering is deterministic.

Two tests pin this: `every emitted event conforms to the catalogue schema` (allowed-set and required-set
containment on every emitted event) and `an attribute the catalogue does not declare is rejected, not exported`
(the builder throws for an unregistered key such as `tramai.governance.debug` fed `exposure.toString()`).

### P2 - source currency and the recorded decision category were promised but not exported

Confirmed - the matrix above promised them and the implementation did not deliver them. Both are now exported:

- `registrationCurrency` and `auditCurrency`, read independently from `view.currency`, with no combined freshness
  flag. A projection read with no fresh observation ports reports `UNKNOWN` on both, never `CURRENT`.
- `recordedDecision`, the bounded safe token the projection contract already validates, exported as a span
  attribute only.

`GovernanceExposure` now carries the two currencies at exposure level and refuses a currency claim on any state
that did not read a projection. The end-to-end test asserts both are `UNKNOWN` for a persisted row read without
observation ports - the case where a naive implementation would report `CURRENT` because the row exists.

### P2 - mutation artifacts committed, unsafe attribute forwarding untested

Confirmed: `GovernanceExposure.mutant` and `GovernanceTelemetryExporter.mutant` were tracked under
`src/main/kotlin`. Both are removed. The M6 "benign survivor" - an unregistered `tramai.governance.debug`
attribute populated from `exposure.toString()` - is no longer a reasoned survivor: it is now **impossible**,
because the catalogue rejects the key and the exporter has no path that renders an object into an attribute. The
test asserts the rejection rather than reasoning about the absence of payloads today.

### P2 - end-to-end qualification

Delivered rather than amended. `GovernanceTelemetryExportPostgresTest` starts a real PostgreSQL container,
applies the read-model migration, seeds a projection through `GovernanceProjectionTestSeeder` - a **test-only
fixture in `:tramai-security`** that runs the real materializer, because the writer and its digest
canonicalisation are internal to that module and a reverse edge would be a cycle - then reads it through the
public `GovernanceQueryService.jdbc(...)` port, maps it with `GovernanceExposure.from` and inspects the exported
span.

A hand-inserted row would have been refused as `InvalidStoredProjection` by the store's digest verification, so
the fixture runs the real writer. What the test establishes: `EVIDENCE_AVAILABLE`; `AUTHORITATIVE_PERSISTED` for a
decision the authoritative audit trail persisted; `NOT_OBSERVED` still visible for the families with no recorded
decision; the reason-code text absent; both currencies `UNKNOWN` with no fresh observation ports.

### P3 - orphaned catalogue comment

Removed. When the governance event was relocated to the top of `allEvents` to stop `spotlessApply` reflowing
pre-existing declarations, its trailing comment block stayed behind with nothing after it. The comment also
repeated the earlier mistaken claim that the static baseline is line-keyed; it is keyed by declaration text.
The event keeps a single correct comment at its actual definition.

### Not changed

The module edge (`:tramai-observability` -> `:tramai-security`, `api`), the default payload exclusions, and every
quality gate, baseline and threshold. No suppression was added and no deviation ceiling moved.
