package dev.tramai.orchestration

class LoggingTramaiWorkerObserver(
    private val logEvent: (String) -> Unit = { LoggingTramaiWorkerObserver.logger.info(it) },
) : TramaiWorkerObserver {
    override fun onWorkerStarted(workerId: String) = logEvent("worker=$workerId event=started")
    override fun onWorkerStopped(workerId: String) = logEvent("worker=$workerId event=stopped")
    override fun onLeaseAcquired(workflowId: String, workerId: String) = logEvent("workflow=$workflowId worker=$workerId event=lease_acquired")
    override fun onLeaseReleased(workflowId: String, workerId: String) = logEvent("workflow=$workflowId worker=$workerId event=lease_released")
    override fun onLeaseExpired(workflowId: String, workerId: String) = logEvent("workflow=$workflowId worker=$workerId event=lease_expired")
    override fun onLeaseRenewalFailed(workflowId: String, workerId: String, error: Throwable) = logEvent("workflow=$workflowId worker=$workerId event=lease_renewal_failed ${failureText(error)}")
    override fun onLeaseReleaseFailed(workflowId: String, workerId: String, error: Throwable) = logEvent("workflow=$workflowId worker=$workerId event=lease_release_failed ${failureText(error)}")
    override fun onPollFailed(workerId: String, error: Throwable) = logEvent("worker=$workerId event=poll_failed ${failureText(error)}")
    override fun onWorkTakenOver(workflowId: String, previousWorkerId: String, newWorkerId: String) = logEvent("workflow=$workflowId event=work_taken_over from=$previousWorkerId to=$newWorkerId")
    override fun onUnknownAttempt(runId: String, stepName: String, priorWorkerId: String, attemptTime: Long) = logEvent("run=$runId step=$stepName event=unknown_attempt prior_worker=$priorWorkerId attempt_time=$attemptTime")
    override fun onStepAttemptStarted(runId: String, stepName: String, attemptId: String, workerId: String) = logEvent("run=$runId step=$stepName attempt=$attemptId worker=$workerId event=attempt_started")
    override fun onStepAttemptCompleted(runId: String, stepName: String, attemptId: String, workerId: String) = logEvent("run=$runId step=$stepName attempt=$attemptId worker=$workerId event=attempt_completed")
    override fun onStepAttemptFailed(runId: String, stepName: String, attemptId: String, workerId: String, error: Throwable) = logEvent("run=$runId step=$stepName attempt=$attemptId worker=$workerId event=attempt_failed ${failureText(error)}")
    override fun onShutdownStarted(workerId: String) = logEvent("worker=$workerId event=shutdown_started")
    override fun onDrainProgress(workerId: String, done: Int, pending: Int) = logEvent("worker=$workerId event=drain_progress done=$done pending=$pending")
    override fun onShutdownComplete(workerId: String) = logEvent("worker=$workerId event=shutdown_complete")
    override fun onWorkerHeartbeat(workerId: String, uptimeMillis: Long, claimedCount: Int) = logEvent("worker=$workerId event=heartbeat uptime=$uptimeMillis claimed=$claimedCount")
    override fun onLeaseRenewed(workflowId: String, workerId: String, newExpiry: Long) = logEvent("workflow=$workflowId worker=$workerId event=lease_renewed expiry=$newExpiry")
    override fun onLeaseContested(workflowId: String, claimantWorkerId: String, currentWorkerId: String) = logEvent("workflow=$workflowId event=lease_contested claimant=$claimantWorkerId current=$currentWorkerId")
    override fun onWorkflowAbandoned(workflowId: String, workerId: String, lastStep: String?, timeoutMillis: Long) = logEvent("workflow=$workflowId worker=$workerId event=workflow_abandoned last_step=$lastStep timeout=$timeoutMillis")

    private fun failureText(error: Throwable): String = "error=${error::class.java.simpleName}"

    companion object {
        private val logger = java.util.logging.Logger.getLogger("dev.tramai.orchestration.LoggingTramaiWorkerObserver")
    }
}
