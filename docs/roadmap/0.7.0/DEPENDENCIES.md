# TramAI 0.7.0 — Epic Dependencies

## Dependency classes

- **HARD** — Epic cannot satisfy its acceptance criteria without the dependency integrated.
- **SOFT** — Epic can begin independently, but must consume/reconcile the dependency contract before completion.
- **NONE** — no known implementation dependency.

## Graph

```text
0.7.1 Control-plane authority / workload identity
   ├──────────────┐
   │              │
   ▼              ▼
0.7.2 Policy   0.7.4 Evidence/projection
   │              ▲
   ▼              │
0.7.3 Selection ──┘
                  │
                  ▼
             0.7.5 Timeline/reconstruction

0.7.1 + 0.7.4
      │
      ▼
0.7.6 AuthN/AuthZ/control
      │
      ▼
0.7.7 Persisted-run cancellation

0.7.3 + 0.7.4 + 0.7.5 + 0.7.6 + 0.7.7
      │
      ▼
0.7.8 Dashboard 2.0 / release integration
```

## Matrix

| Epic | Dependency | Class | Reason |
|---|---|---|---|
| 0.7.2 | 0.7.1 | SOFT | policy/trust can be designed independently but evidence and operational attribution require stable workload/config identity |
| 0.7.3 | 0.7.2 | HARD | authorized/viable/selected stages depend on effective classification/trust/policy authority |
| 0.7.4 | 0.7.1 | HARD | projections require authoritative workload/run/config identities |
| 0.7.4 | 0.7.2/0.7.3 | SOFT | evidence schema can start first but must project their final reason/decision contracts |
| 0.7.5 | 0.7.4 | HARD | timeline/reconstruction consume authoritative evidence/read-model contracts |
| 0.7.6 | 0.7.1/0.7.4 | HARD | privileged commands require target identity/preconditions and evidence linkage |
| 0.7.7 | 0.7.6 | HARD | persisted cancellation is an authoritative privileged lifecycle command |
| 0.7.8 | 0.7.3–0.7.7 | HARD | Dashboard is a client of completed headless control-plane APIs |

## Parallel-start recommendation

At the initial 0.7 baseline:

- **Start:** 0.7.1.
- **May audit/spec in parallel:** 0.7.2 and 0.7.4.
- **Do not finalize implementation contracts yet:** 0.7.3, 0.7.5, 0.7.6–0.7.8 until their HARD dependencies stabilize.

This preserves parallelism without creating duplicate authority models.
