package dev.tramai.engine.budget

import dev.tramai.core.exception.TokenBudgetExceededException
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.OperationObservation
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.TokenBudgetSettings
import dev.tramai.engine.TokenBudgetSnapshot
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Direct contract tests for [TokenBudgetCoordinator].
 *
 * The coordinator owns the event/exception semantics of token-budget
 * enforcement. Event names, attribute names, scopes and the hard-limit
 * exception type are frozen contracts — the extraction must preserve them
 * exactly.
 */
class TokenBudgetCoordinatorTest {

    private fun response(input: Int, output: Int) = ModelResponse(
        content = "ok",
        inputTokens = input,
        outputTokens = output,
    )

    private fun coordinator(settings: TokenBudgetSettings = TokenBudgetSettings()) =
        TokenBudgetCoordinator(settings)

    private class RecordingObservation : OperationObservation {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun onProviderResponse(response: ModelResponse) = Unit
        override fun onProviderFailure(error: Throwable) = Unit
        override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) = Unit
        override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
            events += name to attributes
        }
        override fun onCallCompleted(parseSuccess: Boolean?) = Unit
    }

    @Test
    fun `budget disabled always observes ok and emits nothing`() {
        val c = coordinator(TokenBudgetSettings())
        val t = c.createTracker()
        val obs = RecordingObservation()

        assertThat(c.enforce(t, response(5, 5), obs, "p1", "m1")).isEqualTo(Unit)

        assertThat(obs.events).isEmpty()
    }

    @Test
    fun `missing usage emits usage_unavailable with provider and model`() {
        val c = coordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 100))
        val t = c.createTracker()
        val obs = RecordingObservation()

        c.enforce(t, ModelResponse(content = "no-tokens", inputTokens = null, outputTokens = 1), obs, "p1", "m1")

        assertThat(obs.events).containsExactly(
            "tramai.token_budget.usage_unavailable" to mapOf(
                "provider_id" to "p1",
                "effective_model" to "m1",
            ),
        )
    }

    @Test
    fun `hard attempt limit throws with attempt scope`() {
        val c = coordinator(TokenBudgetSettings(hardMaxTokensPerAttempt = 6))
        val t = c.createTracker()
        val obs = RecordingObservation()

        assertThatThrownBy { c.enforce(t, response(4, 4), obs, "p1", "m1") }
            .isInstanceOfSatisfying(TokenBudgetExceededException::class.java) { e ->
                assertThat(e.scope).isEqualTo("attempt")
                assertThat(e.limitTokens).isEqualTo(6L)
                assertThat(e.observedTokens).isEqualTo(8L)
                assertThat(e.providerId).isEqualTo("p1")
                assertThat(e.modelName).isEqualTo("m1")
            }

        assertThat(obs.events).containsExactly(
            "tramai.token_budget.hard_limit_exceeded" to mapOf(
                "provider_id" to "p1",
                "effective_model" to "m1",
                "limit_tokens" to 6L,
                "observed_tokens" to 8L,
                "scope" to "attempt",
            ),
        )
    }

    @Test
    fun `hard operation limit throws when cumulative usage exceeds`() {
        val c = coordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 10))
        val t = c.createTracker()
        val obs = RecordingObservation()

        c.enforce(t, response(4, 4), obs, "p1", "m1")
        assertThatThrownBy { c.enforce(t, response(2, 2), obs, "p1", "m1") }
            .isInstanceOfSatisfying(TokenBudgetExceededException::class.java) { e ->
                assertThat(e.scope).isEqualTo("operation")
                assertThat(e.observedTokens).isEqualTo(12L)
            }
    }

    @Test
    fun `soft operation limit emits warning once and does not throw`() {
        val c = coordinator(TokenBudgetSettings(softMaxTokensPerOperation = 10))
        val t = c.createTracker()
        val obs = RecordingObservation()

        c.enforce(t, response(6, 6), obs, "p1", "m1") // 12 observed — warning
        c.enforce(t, response(1, 1), obs, "p1", "m1") // 14 — already warned

        assertThat(obs.events).containsExactly(
            "tramai.token_budget.soft_limit_exceeded" to mapOf(
                "provider_id" to "p1",
                "effective_model" to "m1",
                "limit_tokens" to 10L,
                "observed_tokens" to 12L,
                "scope" to "operation",
            ),
        )
    }

    @Test
    fun `multiple responses accumulate toward the same operation budget`() {
        val c = coordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 20))
        val t = c.createTracker()
        val obs = RecordingObservation()

        c.enforce(t, response(3, 2), obs, "p1", "m1") // 5
        c.enforce(t, response(4, 4), obs, "p1", "m1") // 13
        c.enforce(t, response(2, 2), obs, "p1", "m1") // 17
        c.enforce(t, response(1, 1), obs, "p1", "m1") // 19 ok
        assertThatThrownBy { c.enforce(t, response(1, 1), obs, "p1", "m1") } // 21
            .isInstanceOf(TokenBudgetExceededException::class.java)
    }

    @Test
    fun `snapshot after usage and restore continues the same operation budget`() {
        val c = coordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 20))
        val t = c.createTracker()
        val obs = RecordingObservation()

        c.enforce(t, response(5, 5), obs, "p1", "m1") // 10 observed
        val snapshot = t.snapshot()
        assertThat(snapshot.totalInputTokens).isEqualTo(5L)
        assertThat(snapshot.totalOutputTokens).isEqualTo(5L)

        // suspended, restored fresh from snapshot
        val restored = c.restoreTracker(snapshot)
        c.enforce(restored, response(5, 5), obs, "p1", "m1") // 20 total — ok
        assertThatThrownBy { c.enforce(restored, response(1, 1), obs, "p1", "m1") } // 22
            .isInstanceOf(TokenBudgetExceededException::class.java)
    }

    @Test
    fun `null snapshot restores a fresh tracker`() {
        val c = coordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 20))
        val t = c.restoreTracker(snapshot = null)

        // 10 and 20 observed are within the 20 limit; 22 crosses it
        c.enforce(t, response(5, 5), RecordingObservation(), "p1", "m1")
        c.enforce(t, response(5, 5), RecordingObservation(), "p1", "m1")
        assertThatThrownBy { c.enforce(t, response(1, 1), RecordingObservation(), "p1", "m1") }
            .isInstanceOf(TokenBudgetExceededException::class.java)
    }

    @Test
    fun `restored soft-warning state prevents a second warning`() {
        val c = coordinator(TokenBudgetSettings(softMaxTokensPerOperation = 10))
        val t = c.createTracker()
        val obs = RecordingObservation()

        c.enforce(t, response(6, 6), obs, "p1", "m1") // warning emitted
        val snapshot = t.snapshot()
        assertThat(snapshot.warnIfExceeded).isFalse()

        val restored = c.restoreTracker(snapshot)
        val obs2 = RecordingObservation()
        c.enforce(restored, response(1, 1), obs2, "p1", "m1")
        assertThat(obs2.events).isEmpty()
    }

    @Test
    fun `resumed usage breaches the accumulated hard limit`() {
        val c = coordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 20))
        val t = c.createTracker()
        val obs = RecordingObservation()

        c.enforce(t, response(5, 5), obs, "p1", "m1") // 10
        val snapshot = t.snapshot()
        val restored = c.restoreTracker(snapshot)

        c.enforce(restored, response(5, 5), obs, "p1", "m1") // 20 — at limit, ok
        assertThatThrownBy { c.enforce(restored, response(1, 1), obs, "p1", "m1") } // 22
            .isInstanceOfSatisfying(TokenBudgetExceededException::class.java) { e ->
                assertThat(e.scope).isEqualTo("operation")
            }
    }

    @Test
    fun `createTracker and restoreTracker produce independent trackers`() {
        val c = coordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 100))
        val a = c.createTracker()
        val b = c.createTracker()
        val obs = RecordingObservation()

        c.enforce(a, response(30, 30), obs, "p1", "m1") // a: 60
        c.enforce(b, response(30, 30), obs, "p1", "m1") // b: 60
        c.enforce(b, response(15, 15), obs, "p1", "m1") // b: 90
        c.enforce(a, response(15, 15), obs, "p1", "m1") // a: 90
        c.enforce(b, response(5, 5), obs, "p1", "m1") // b: 100 — at limit, ok
        assertThatThrownBy { c.enforce(b, response(5, 5), RecordingObservation(), "p1", "m1") } // b: 110 — breach
            .isInstanceOf(TokenBudgetExceededException::class.java)
        // a untouched: still 90
        c.enforce(a, response(5, 5), obs, "p1", "m1") // a: 100 — at limit, ok
        assertThat(obs.events).isEmpty()
    }
}
