# SPEC-112: Approved Continuation Resume Worker

## 1. Executive Summary

Add a runtime-owned background worker that finds approved + pending continuations, loads the internal sealed resume credential, calls `ApprovalResumeControlPlane.resume(...)`, deletes the credential after success, and records safe audit evidence. Never exposes resume tokens.

## 2. Design

### 2.1 Worker API

```kotlin
interface ApprovedContinuationResumeWorker {
    suspend fun runOnce(limit: Int = 50): ApprovedContinuationResumeWorkerResult
}

data class ApprovedContinuationResumeWorkerResult(
    val scanned: Int,
    val resumed: Int,
    val skipped: Int,
    val failed: Int,
)
```

### 2.2 Claim SPI

```kotlin
interface ApprovedContinuationResumeQueue {
    suspend fun claimApprovedPending(
        workerId: String,
        limit: Int,
        leaseUntil: Instant,
    ): List<ApprovedContinuationResumeWorkItem>

    suspend fun markResumeSucceeded(approvalId: ApprovalId, workerId: String)
    suspend fun markResumeFailed(approvalId: ApprovalId, workerId: String, reasonCode: String, retryAt: Instant? = null)
}

data class ApprovedContinuationResumeWorkItem(
    val approvalId: ApprovalId,
    val approvalVersion: Long,
    val continuationVersion: Long,
    val workflowRunId: String,
)
```

### 2.3 Worker implementation

The worker:
1. Calls `queue.claimApprovedPending(...)` to get work items
2. For each item, loads the sealed credential via `ApprovalResumeCredentialStore.get(...)`
3. If credential missing → `markResumeFailed(..., "credential-not-found")` and continue
4. Calls `ApprovalResumeControlPlane.resume(ApprovalResumeCommand(...))`
5. On `Resumed` or `AlreadyCompleted` → `markResumeSucceeded(...)` + `delete(approvalId)`
6. On `NotApproved` or `NotFound` → `markResumeFailed(...)` terminal, keep credential
7. On `Conflict("approval-continuation-expired")` → `markResumeFailed(...)` terminal, delete credential
8. On `Failed` or `Conflict` other → `markResumeFailed(...)` retryable, keep credential

### 2.4 Configuration

```yaml
tramai:
  sovereign:
    ops:
      approved-resume-worker:
        enabled: false
        worker-id: tramai-approved-resume-worker
        batch-size: 50
        lease-duration: 2m
        lease-heartbeat-interval: 30s
```

## 3. Persistence

JDBC queue implementation:

```sql
SELECT a.approval_id, a.version, c.version
FROM approvals a
JOIN approval_continuations c ON c.approval_id = a.approval_id
JOIN tramai_approval_resume_credentials rc ON rc.approval_id = a.approval_id
WHERE a.status = 'APPROVED'
  AND c.status = 'PENDING'
  AND c.approval_expires_at > now()
  AND rc.expires_at > now()
ORDER BY c.approval_expires_at ASC
LIMIT ?
FOR UPDATE SKIP LOCKED
```

## 4. Tests

### 4.1 Worker tests
- approved pending continuation resumes once
- denied approval is skipped
- missing credential fails closed
- resume success deletes credential
- already completed reconciles credential
- resume failed keeps credential for retry

### 4.2 JDBC queue tests
- two workers claim same item → exactly one resumes
- batch limit respected
- oldest expiry first ordering

### 4.3 E2E
- Extend regulated claim triage flow with auto-resume
