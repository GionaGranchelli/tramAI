# Orchestration Persistence

`tramai-orchestration` keeps workflow persistence storage-agnostic.

That separation is intentional:

- the workflow runtime owns step ordering, checkpoint timing, and resume rules
- your application chooses how state is serialized
- your application chooses where checkpoints live
- multi-node ownership is optional and added separately through leasing

## Core Pieces

The persistence API has three main parts:

- `WorkflowStateCodec<S>`: encodes and decodes typed workflow state
- `WorkflowCheckpointStore`: stores revisioned checkpoints
- `WorkflowPersistence<S>`: wires the codec and store into one workflow run

The repository includes small reference stores:

- `InMemoryWorkflowCheckpointStore`
- `FileWorkflowCheckpointStore`
- `MarkdownWorkflowCheckpointStore`
- `JdbcWorkflowCheckpointStore`

For lease-based ownership, the repository includes:

- `InMemoryWorkflowLeaseStore`
- `FileWorkflowLeaseStore`
- `JdbcWorkflowLeaseStore`

These are conveniences, not the architectural boundary. You can replace them with your own implementation for Postgres, S3, GCS, Redis, a document store, or something domain-specific.

## Custom Codec

The codec is the right place to choose JSON, YAML, protobuf-over-base64, markdown front matter, or a domain-specific envelope.

```kotlin
data class ReviewState(
    val requestId: String,
    val draft: String? = null,
    val approved: Boolean = false,
)

object ReviewStateCodec : WorkflowStateCodec<ReviewState> {
    override fun encode(state: ReviewState): String = listOf(
        state.requestId,
        state.draft.orEmpty(),
        state.approved.toString(),
    ).joinToString("|")

    override fun decode(payload: String): ReviewState {
        val parts = payload.split("|", limit = 3)
        return ReviewState(
            requestId = parts[0],
            draft = parts.getOrNull(1).orEmpty().ifBlank { null },
            approved = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: false,
        )
    }
}
```

For richer state, JSON is usually the most practical choice.

## Custom Store

Implement `WorkflowCheckpointStore` when you need a storage backend the repository does not provide:

```kotlin
class MyCheckpointStore(
    private val client: CheckpointClient,
) : WorkflowCheckpointStore {
    override suspend fun load(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint? = client.read(workflowName, workflowId)

    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint {
        return client.write(
            checkpoint = checkpoint,
            expectedRevision = expectedRevision,
        )
    }

    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
    ) {
        client.delete(
            workflowName = workflowName,
            workflowId = workflowId,
            expectedRevision = expectedRevision,
        )
    }
}
```

The important contract detail is revision handling:

- first save usually uses `expectedRevision = null`
- later saves use the current revision
- stale writers should fail with `WorkflowCheckpointConflictException`

That gives database, object-store, and filesystem implementations the same optimistic-concurrency model.

## Wiring A Workflow

Once you have a codec and store, attach them through `WorkflowPersistence`:

```kotlin
val workflow = workflow<ReviewState>(
    name = "review-workflow",
    definitionVersion = "review-v1",
) {
    localStep(
        name = "draft",
        transform = { state, _ -> state.copy(draft = "draft:${state.requestId}") },
    )
    gateStep(
        name = "approval",
        decide = { state, _ ->
            if (state.approved) GateDecision.allow()
            else GateDecision.reject("approval required")
        },
    )
}.build { it.draft ?: error("draft must exist") }

val persistence = WorkflowPersistence(
    checkpointStore = MyCheckpointStore(client),
    stateCodec = ReviewStateCodec,
)

val result = workflow.run(
    initialState = ReviewState(requestId = "invoice-123", approved = true),
    persistence = persistence,
)
```

To resume:

```kotlin
val result = workflow.resume(
    context = WorkflowContext(workflowId = "invoice-123"),
    persistence = persistence,
)
```

## Resume Compatibility Contract

Resume is intentionally strict.

A checkpoint may resume only when all of the following are true:

- the checkpoint exists for the same workflow name and `workflowId`
- the checkpoint was written with the runtime's required definition metadata
- the current workflow uses the same explicit `definitionVersion`
- the current workflow definition digest matches the persisted digest

The persisted digest is structural. It includes:

- workflow name
- top-level and nested step topology
- step names and branch keys
- stop-policy compatibility inputs such as max step executions and max parallel branches

Practical consequences:

- changing workflow structure without changing `definitionVersion` still fails loudly on resume
- changing `definitionVersion` intentionally also fails loudly on resume against older checkpoints
- checkpoints created before the stable resume-compatibility metadata contract are rejected rather than resumed heuristically

For persisted workflows, treat `definitionVersion` as an intentional operator-controlled compatibility boundary.

## Multi-Node Ownership

Revision checks prevent stale writes, but they do not by themselves guarantee that only one executor is actively working on a workflow at a time.

For that case, use `WorkflowLeaseStore` and `WorkflowLeasePolicy`.

```kotlin
val persistence = WorkflowPersistence(
    checkpointStore = MyCheckpointStore(client),
    stateCodec = ReviewStateCodec,
    leaseStore = MyLeaseStore(client),
    leasePolicy = WorkflowLeasePolicy(
        ownerId = "worker-7",
        leaseDurationMillis = 30_000,
    ),
)
```

When leasing is configured:

- `run(...)` claims the workflow before checkpointing starts
- `resume(...)` claims the workflow before resumed execution starts
- each successful checkpoint write renews the lease with the latest checkpoint revision
- success or failure releases the lease

That model is useful when you have:

- multiple workers polling the same queue
- a scheduler that may retry work on another node
- long-running workflows where ownership should be explicit

## Lease Store Contract

Implement `WorkflowLeaseStore` when you need active ownership:

```kotlin
class MyLeaseStore(
    private val client: LeaseClient,
) : WorkflowLeaseStore {
    override suspend fun currentLease(
        workflowName: String,
        workflowId: String,
    ): WorkflowLease? = client.current(workflowName, workflowId)

    override suspend fun claim(
        workflowName: String,
        workflowId: String,
        ownerId: String,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease = client.claim(
        workflowName = workflowName,
        workflowId = workflowId,
        ownerId = ownerId,
        checkpointRevision = checkpointRevision,
        leaseDurationMillis = leaseDurationMillis,
    )

    override suspend fun renew(
        lease: WorkflowLease,
        checkpointRevision: Long?,
        leaseDurationMillis: Long,
    ): WorkflowLease = client.renew(
        lease = lease,
        checkpointRevision = checkpointRevision,
        leaseDurationMillis = leaseDurationMillis,
    )

    override suspend fun release(lease: WorkflowLease) {
        client.release(lease)
    }
}
```

If another executor already owns the workflow, the store should fail with `WorkflowLeaseConflictException`.

## File-Backed Leases

For local tooling or single-filesystem deployments, `FileWorkflowLeaseStore` gives you active ownership without introducing a database:

```kotlin
val persistence = WorkflowPersistence(
    checkpointStore = FileWorkflowCheckpointStore(Path.of(".tramai/workflows")),
    stateCodec = ReviewStateCodec,
    leaseStore = FileWorkflowLeaseStore(Path.of(".tramai/workflows")),
    leasePolicy = WorkflowLeasePolicy(
        ownerId = "worker-local-1",
        leaseDurationMillis = 30_000,
    ),
)
```

This is a good fit when:

- one host or one shared filesystem coordinates work
- operators want inspectable files instead of database rows
- the deployment is simple enough that file locking is acceptable

This is not the right fit for broader distributed deployments where filesystem semantics are weak or inconsistent across nodes.

## JDBC Lease Guidance

For multi-node backends already centered on a relational database, JDBC is usually the most practical lease implementation approach.

The repository includes `JdbcWorkflowLeaseStore` as a reference adapter. It pairs naturally with `JdbcWorkflowCheckpointStore`, but it can also be used with another checkpoint backend if your ownership and persistence concerns live in different systems.

A reference lease table can look like this:

```sql
CREATE TABLE tramai_workflow_lease (
    workflow_name VARCHAR(255) NOT NULL,
    workflow_id VARCHAR(255) NOT NULL,
    lease_id VARCHAR(255) NOT NULL,
    owner_id VARCHAR(255) NOT NULL,
    checkpoint_revision BIGINT NULL,
    acquired_at_epoch_millis BIGINT NOT NULL,
    expires_at_epoch_millis BIGINT NOT NULL,
    PRIMARY KEY (workflow_name, workflow_id)
);
```

The key operational rule is that lease ownership is time-bound. An active row with `expires_at_epoch_millis > now` blocks another owner. An expired row may be replaced by a new claim.

Reference SQL patterns:

`claim`

```sql
INSERT INTO tramai_workflow_lease (
    workflow_name,
    workflow_id,
    lease_id,
    owner_id,
    checkpoint_revision,
    acquired_at_epoch_millis,
    expires_at_epoch_millis
) VALUES (?, ?, ?, ?, ?, ?, ?);
```

If the primary key already exists, load the current row:

- if `expires_at_epoch_millis <= now`, replace it atomically
- otherwise fail with `WorkflowLeaseConflictException`

`renew`

```sql
UPDATE tramai_workflow_lease
SET
    checkpoint_revision = ?,
    expires_at_epoch_millis = ?
WHERE workflow_name = ?
  AND workflow_id = ?
  AND lease_id = ?
  AND owner_id = ?
  AND expires_at_epoch_millis > ?;
```

If the update count is zero:

- the lease may have expired
- another owner may have replaced it
- the workflow should treat that as a lease conflict

`release`

```sql
DELETE FROM tramai_workflow_lease
WHERE workflow_name = ?
  AND workflow_id = ?
  AND lease_id = ?
  AND owner_id = ?;
```

Recommended implementation notes:

- index `(owner_id)` if you want operator visibility into active worker ownership
- use the same clock source for claim and renewal decisions where possible
- keep lease duration comfortably larger than the expected time between checkpoint writes
- include `checkpoint_revision` in the row so operators can correlate ownership with persisted state progress
- treat zero-row updates on renew as conflicts, not silent no-ops

## Choosing A Backend

Good fits by use case:

- JDBC store: transactional application backends already centered on a relational database
- file store: local tooling, development environments, small single-node deployments
- file lease store: local or shared-filesystem ownership where operators want visible lease artifacts
- markdown store: auditable local artifacts or review-oriented workflow traces
- custom object-store backend: large payloads, cheap durable snapshots, low coordination needs

Use a custom store when your operational model needs:

- a different naming or partitioning strategy
- encryption or tenant-aware isolation
- lifecycle rules tied to business retention
- cloud-native locks or conditional writes

## Current Boundary

The current orchestration persistence model is intentionally narrow:

- checkpoints happen only at top-level workflow step boundaries
- nested branch internals are not resumed mid-step
- in-flight parallel work is not resumed mid-branch
- partially emitted provider streams are not resumed token-by-token

That boundary keeps the SPI auditable and stable while leaving room for later durability work.
