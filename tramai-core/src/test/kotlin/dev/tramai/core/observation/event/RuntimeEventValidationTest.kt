package dev.tramai.core.observation.event

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RuntimeEventValidationTest {
    private val stepStarted = RuntimeEventCatalogue.event("tramai.workflow.step.started")

    @Test
    fun `valid event succeeds`() {
        val event = RuntimeEvent.of(stepStarted) {
            set(RuntimeAttributes.STEP_NAME, "validate-input")
        }
        RuntimeEventValidator.validateTypes(event)
        assertThat(event.attribute(RuntimeAttributes.STEP_NAME)).isEqualTo("validate-input")
    }

    @Test
    fun `unknown event name is rejected`() {
        assertThatThrownBy { RuntimeEvent.of("tramai.workflow.nonexistent") {} }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unknown runtime event")
    }

    @Test
    fun `attribute not allowed for the event is rejected`() {
        assertThatThrownBy {
            RuntimeEvent.of(stepStarted) {
                set(RuntimeAttributes.STEP_NAME, "x")
                set(RuntimeAttributes.ERROR_TYPE, "IllegalStateException")
            }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not allowed")
    }

    @Test
    fun `missing required attribute is rejected`() {
        assertThatThrownBy { RuntimeEvent.of(stepStarted) {} }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("missing required attributes")
    }

    @Test
    fun `wrong attribute value type is rejected by the validator`() {
        // The typed builder prevents wrong types at compile time; the runtime
        // validator guards values that reach an event by other paths (legacy
        // Map adaptation). Construct one directly to prove the check.
        val corrupt = RuntimeEvent(
            RuntimeEventCatalogue.event("tramai.worker.heartbeat"),
            mapOf(
                RuntimeAttributes.WORKER_ID.name to "w1",
                RuntimeAttributes.WORKER_UPTIME_MS.name to "not-a-long",
            ),
        )
        assertThatThrownBy { RuntimeEventValidator.validateTypes(corrupt) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("expected Long")
    }

    @Test
    fun `attribute from another event is rejected`() {
        // WORKER_ID is not allowed on a workflow step event.
        assertThatThrownBy {
            RuntimeEvent.of(stepStarted) {
                set(RuntimeAttributes.STEP_NAME, "x")
                set(RuntimeAttributes.WORKER_ID, "w1")
            }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not allowed")
    }

    @Test
    fun `dynamic workflow context namespace composes keys as documented`() {
        val namespace = DynamicAttributeNamespaces.WORKFLOW_CONTEXT
        assertThat(namespace.key("tenant")).isEqualTo("tramai.workflow.context.tenant")
        assertThat(namespace.matches("tramai.workflow.context.tenant")).isTrue()
        assertThat(namespace.matches("tramai.workflow.context")).isFalse()
        assertThat(namespace.matches("tramai.workflow.name")).isFalse()
    }
}
