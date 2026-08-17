package dev.tramai.engine.approval

import dev.tramai.core.approval.*
import dev.tramai.core.exception.*
import dev.tramai.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ContinuationClaimServiceTest {
    private val digest = Sha256Digest.of("sha256:" + "1".repeat(64))
    private fun continuation(status: ApprovalContinuationStatus = ApprovalContinuationStatus.PENDING, version: Long = 3) = ApprovalContinuation("a","wf","c","call","tool",digest,"p",digest,status,Instant.EPOCH,Instant.MAX,null,null,null,version=version)
    private fun command() = ResumeApprovalCommand("a", 1, 3, ApprovalToken.parsePresented("token"), "me")
    private fun metadata() = SuspendedInvocationMetadata("a","call","tool",0,"c",EngineExecutionIdentity("wf","c",digest,"p","actor"),ExecutionSecurityContext(),ResumeOperationReference("x","m","()V",digest),digest,toolReference=ResumeToolReference("tool",digest))
    private fun registered() = RegisteredResumeOperation(metadata().operationReference, approvalService(approvalOperation(ApprovalRegistryService::class.java.getMethod("first"))), approvalOperation(ApprovalRegistryService::class.java.getMethod("first")), StubResumeExecutor())
    private class Store(var value: ApprovalContinuation? = null) : ApprovalContinuationStore {
        var getCalls=0; var claim: Triple<String,Long,String>?=null; var complete: Triple<String,Long,String>?=null; var cancel: Pair<String,Long>?=null; var failure: Throwable?=null
        private fun fail(){ failure?.let { throw it } }
        override suspend fun create(c: ApprovalContinuation,a: SensitiveToolArguments)=c; override suspend fun get(id:String):ApprovalContinuation? { getCalls++; fail(); return value }; override suspend fun claimForExecution(id:String,v:Long,b:String):ClaimedApprovalContinuation { claim=Triple(id,v,b); fail(); return ClaimedApprovalContinuation(requireNotNull(value),SensitiveToolArguments.of("{}")) }; override suspend fun complete(id:String,v:Long,b:String):ApprovalContinuation { complete=Triple(id,v,b); fail(); return requireNotNull(value) }; override suspend fun cancel(id:String,v:Long):ApprovalContinuation { cancel=id to v; fail(); return requireNotNull(value) }; override suspend fun expire(id:String,v:Long)=requireNotNull(value); override suspend fun findStaleClaimed(i:Instant,l:Int)=emptyList<ApprovalContinuation>(); override suspend fun forceCancelClaimed(a:String,b:Long,c:String,d:String)=requireNotNull(value); override suspend fun sweepExpired()=0
    }
    @Test fun `completed and missing continuation are rejected`() = runTest { val s=Store(continuation(ApprovalContinuationStatus.COMPLETED)); val service=ContinuationClaimService(s); assertThatThrownBy { kotlinx.coroutines.runBlocking { service.loadPendingForResume(s,command(),metadata(),registered()) } }.isInstanceOf(ApprovalTokenRejectedException::class.java); s.value=null; assertThatThrownBy { kotlinx.coroutines.runBlocking { service.loadPendingForResume(s,command(),metadata(),registered()) } }.isInstanceOf(ApprovalNotFoundException::class.java) }
    @Test fun `happy load reads once`() = runTest { val s=Store(continuation()); assertThat(ContinuationClaimService(s).loadPendingForResume(s,command(),metadata(),registered())).isSameAs(s.value); assertThat(s.getCalls).isEqualTo(1) }
    @Test fun `bindings expose their stable invariant codes`() { val base=continuation(); val service=ContinuationClaimService(null); fun check(c:ApprovalContinuation=base,m:SuspendedInvocationMetadata=metadata(),cmd:ResumeApprovalCommand=command(),code:String) { assertThatThrownBy { service.validateBindings(c,cmd,m,registered()) }.hasMessage(code) }; check(base.copy(workflowRunId="x"),code="cross-store-mismatch-workflow-run-id"); check(base.copy(correlationId="x"),code="cross-store-mismatch-correlation-id"); check(m=metadata().copy(identity=metadata().identity.copy(correlationId="x")),code="metadata-identity-mismatch-correlation-id"); check(base.copy(workflowDigest=Sha256Digest.of("sha256:"+"2".repeat(64))),code="cross-store-mismatch-workflow-digest"); check(base.copy(policyVersion="x"),code="cross-store-mismatch-policy-version"); check(base.copy(toolName="x"),code="continuation-tool-name-mismatch"); check(base.copy(toolCallId="x"),code="continuation-tool-call-id-mismatch"); check(base.copy(status=ApprovalContinuationStatus.COMPLETED),code="continuation-not-pending"); check(base.copy(version=2),code="continuation-version-mismatch"); check(m=metadata().copy(approvalId="x"),code="metadata-approval-id-mismatch"); check(base.copy(approvalId="x"),code="continuation-approval-id-mismatch") }
    @Test fun `claim complete cancel forward fences and cancellation`() = runTest { val s=Store(continuation()); val service=ContinuationClaimService(s); service.claim("a",3,"me"); service.complete("a",4,"me"); service.cancel("a",5); assertThat(s.claim.toString()).isEqualTo("(a, 3, me)"); assertThat(s.complete.toString()).isEqualTo("(a, 4, me)"); assertThat(s.cancel.toString()).isEqualTo("(a, 5)"); s.failure=CancellationException("stop"); assertThatThrownBy { kotlinx.coroutines.runBlocking { service.claim("a",3,"me") } }.isInstanceOf(CancellationException::class.java) }
}
