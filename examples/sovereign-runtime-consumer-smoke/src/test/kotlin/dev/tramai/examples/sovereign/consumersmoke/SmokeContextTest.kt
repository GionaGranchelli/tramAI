package dev.tramai.examples.sovereign.consumersmoke

import dev.tramai.spring.sovereign.ops.outbox.RecordingSovereignOpsAuditOutboxWorkerObserver
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerObserver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

/**
 * Consumer-resolution smoke test for sovereign runtime modules.
 *
 * Proves that an external application can resolve the published
 * sovereign runtime starters from mavenLocal(), load a Spring context,
 * and verify the expected beans exist.
 *
 * No real AI calls, no real filesystem persistence, no external network.
 */
@SpringBootTest
class SmokeContextTest {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var observer: SovereignOpsAuditOutboxWorkerObserver

    @Test
    fun `Spring context loads with sovereign starters on classpath`() {
        val app = applicationContext.getBean(SmokeApplication::class.java)
        assertThat(app).isNotNull
    }

    @Test
    fun `observer fallback is RecordingObserver when no OpenTelemetry bean is present`() {
        assertThat(observer).isInstanceOf(RecordingSovereignOpsAuditOutboxWorkerObserver::class.java)
    }
}
