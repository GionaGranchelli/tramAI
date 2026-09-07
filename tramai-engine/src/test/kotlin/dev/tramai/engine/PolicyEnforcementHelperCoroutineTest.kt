package dev.tramai.engine

import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

class PolicyEnforcementHelperCoroutineTest {
    @Test
    fun `evaluate resumes a genuinely suspending policy engine`() {
        runBlocking {
            val helper =
                PolicyEnforcementHelper(
                    policyEngine =
                        PolicyEngine {
                            delay(1)
                            PolicyDecision.Allow
                        },
                    migrationWarningGuard = AtomicBoolean(true),
                )

            assertThat(helper.evaluate(testContext())).isEqualTo(PolicyDecision.Allow)
        }
    }

    private fun testContext() =
        PolicyContext(
            enforcementPoint = EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
            correlationId = "coroutine-policy-test",
            actorId = "system.test",
            policyVersion = "test",
        )
}
