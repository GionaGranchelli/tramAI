package dev.tramai.orchestration

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class LoggingTramaiWorkerObserverTest {
    @Test
    fun `log output contains structured key value pairs`() {
        val captured = mutableListOf<String>()
        val observer = LoggingTramaiWorkerObserver(logEvent = { captured.add(it) })

        observer.onWorkerStarted("worker-1")
        observer.onLeaseAcquired("wf-1", "worker-1")
        observer.onWorkerStopped("worker-1")

        assertThat(captured).anySatisfy { assertThat(it).contains("worker=worker-1 event=started") }
        assertThat(captured).anySatisfy { assertThat(it).contains("workflow=wf-1") }
        assertThat(captured).anySatisfy { assertThat(it).contains("event=lease_acquired") }
        assertThat(captured).anySatisfy { assertThat(it).contains("event=stopped") }
    }

    @Test
    fun `new events produce correct log lines`() {
        val captured = mutableListOf<String>()
        val observer = LoggingTramaiWorkerObserver(logEvent = { captured.add(it) })

        observer.onWorkerHeartbeat("worker-2", 5000L, 3)
        observer.onLeaseRenewed("wf-1", "worker-2", 99999L)
        observer.onLeaseContested("wf-2", "worker-3", "worker-2")
        observer.onWorkflowAbandoned("wf-3", "worker-2", "step-5", 30000L)

        assertThat(captured).anySatisfy { assertThat(it).contains("event=heartbeat") }
        assertThat(captured).anySatisfy { assertThat(it).contains("event=lease_renewed") }
        assertThat(captured).anySatisfy { assertThat(it).contains("event=lease_contested") }
        assertThat(captured).anySatisfy { assertThat(it).contains("event=workflow_abandoned") }
    }
}
