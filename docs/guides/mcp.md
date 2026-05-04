# MCP Integration

`tramai-mcp` exposes registered workflows as MCP tools.

This is the adapter for local or remote agent clients that should discover and run workflows through the Model Context Protocol instead of through raw REST calls.

## Activation Model

The MCP auto-configuration only activates when the workflow server beans exist.

In practice, that means:

- `tramai-server` must already be in the application
- `tramai-mcp` layers MCP on top of the existing workflow registry and run store

It is an adapter, not a second orchestration runtime.

## Current Tool Surface

The implemented server exposes four tools:

| Tool | Purpose |
| --- | --- |
| `list_workflows` | Return registered workflows with input and output JSON schema. |
| `run_workflow` | Start a workflow run from a JSON state object. |
| `resume_workflow` | Resume a suspended run by workflow id. |
| `get_workflow_status` | Fetch current status, history, result, and error state. |

The schemas are generated from the registered workflow state and result types through the structured-output handler.

## Transport Options

Current configuration supports two transports.

### Stdio

Use stdio for local agent processes:

```yaml
tramai:
  mcp:
    stdio:
      enabled: true
```

This starts an MCP stdio session inside the application process.

### SSE

Use SSE when you want a network transport:

```yaml
tramai:
  mcp:
    sse:
      enabled: true
      host: 127.0.0.1
      port: 8091
      path: /mcp
```

Important current behavior:

- the SSE transport is served by an embedded Ktor server
- it is separate from the Spring Boot HTTP port
- enabling it does not reuse the main `server.port`

## Relationship To REST

The current implementation maps MCP requests to the existing server controller and run store logic.

That means MCP behavior inherits:

- workflow validation
- idempotency behavior
- conflict handling
- run status and detail payload semantics

For example, `run_workflow` ultimately uses the same JSON-state decoding path as `POST /workflows/{name}/run`.

## Error Mapping

The adapter normalizes several server-side exceptions into MCP tool failures:

- unknown workflow -> `not_found`
- unknown run -> `not_found`
- resume/state conflict -> `conflict`
- invalid input JSON -> `invalid_request`
- unexpected failure -> `internal_error`

That keeps agent clients from seeing Spring-specific exception shapes.

## Good Uses

Use the MCP module when:

- a local coding or agent tool should discover workflows automatically
- workflow invocation should look like normal MCP tool usage
- you want workflow input/output contracts exposed as JSON schema to the client

Do not add it just because you have a server. If no MCP client will consume it, the server API is enough.

## Current Limits

The current implementation is intentionally small:

- it does not expose cancellation as an MCP tool
- it does not expose every server-side administrative endpoint
- it depends on the workflow server module being present

## Related Pages

- [Workflow Server](./server.md)
- [SPEC-014: TramAI Server](../specs/spec-014-server.md)
