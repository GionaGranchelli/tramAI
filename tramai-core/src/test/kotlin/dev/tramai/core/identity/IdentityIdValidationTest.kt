package dev.tramai.core.identity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

/**
 * Atomic id validation. Every typed id must fail closed on the same malformed
 * inputs and accept opaque, case-preserving identifiers without grammar
 * restrictions or silent normalization.
 */
class IdentityIdValidationTest {
    private val overLimit = "a".repeat(129)

    private fun assertRejected(
        construct: (String) -> Any,
        vararg invalid: String,
    ) {
        for (raw in invalid) {
            assertThatThrownBy { construct(raw) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("must not")
        }
    }

    @Test
    fun `blank id is rejected`() {
        assertRejected({ WorkloadId(it) }, "", "   ", "\t")
    }

    @Test
    fun `leading or trailing whitespace is rejected`() {
        assertRejected({ WorkloadId(it) }, " workload", "workload ", " workload ")
    }

    @Test
    fun `control characters are rejected`() {
        assertRejected({ WorkloadId(it) }, "work\nload", "work\u0000load", "work\rload")
    }

    @Test
    fun `over-limit id is rejected`() {
        assertThatThrownBy { WorkloadId(overLimit) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("128")
    }

    @Test
    fun `every atomic id type validates its own value`() {
        val constructs =
            listOf<(String) -> Any>(
                { WorkloadId(it) },
                { ConfigurationId(it) },
                { ConfigurationVersion(it) },
                { EnvironmentId(it) },
                { DeploymentId(it) },
                { RunId(it) },
            )
        for (construct in constructs) {
            assertThatThrownBy { construct(" ") }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { construct(overLimit) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { construct("bad\u0000id") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `opaque identifiers without restricted grammar are accepted`() {
        val accepted =
            listOf(
                "claims",
                "claims-v2",
                "org.example.claims",
                "production/eu",
                "deployment_01",
                "urn:company:workload:claims",
                "customer-support/v3",
            )
        for (raw in accepted) {
            assertThat(WorkloadId(raw).value).isEqualTo(raw)
            assertThat(RunId(raw).value).isEqualTo(raw)
        }
    }

    @Test
    fun `boundary length id is accepted and over-limit rejected`() {
        val atLimit = "a".repeat(128)
        assertThat(WorkloadId(atLimit).value).isEqualTo(atLimit)
        assertRejected({ WorkloadId(it) }, overLimit)
    }

    @Test
    fun `case is preserved and not normalized`() {
        val upper = WorkloadId("Payments")
        val lower = WorkloadId("payments")

        assertThat(upper.value).isEqualTo("Payments")
        assertThat(lower.value).isEqualTo("payments")
        assertThat(upper).isNotEqualTo(lower)
    }

    @Test
    fun `equal values produce equal ids and same hash`() {
        val a = WorkloadId("claims")
        val b = WorkloadId("claims")

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }
}
