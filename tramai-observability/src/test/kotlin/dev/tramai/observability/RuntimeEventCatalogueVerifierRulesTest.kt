package dev.tramai.observability

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mutation-semantics tests for the repository-wide source verifier
 * (verifier.classifySourceLiteral).
 *
 * The fail-closed claim of Epic 5.2 requires that a production literal is an
 * offender even when (a) the exact value already exists in the catalogue, or
 * (b) it merely resembles a declared configuration namespace. Only the exact
 * declared configuration-property literals may appear as `tramai.` strings.
 */
class RuntimeEventCatalogueVerifierRulesTest {

    private val verifier = RuntimeEventCatalogueArchitectureTest()

    @Test
    fun `known catalogue identifier outside the catalogue is an offender`() {
        // "tramai.workflow.completed" is a real catalogue event name — but a
        // production consumer must reference RuntimeEvents.WORKFLOW_COMPLETED,
        // never repeat the underlying string.
        assertFalse(
            verifier.classifySourceLiteral("tramai.workflow.completed"),
            "catalogue identifier literal outside the catalogue must fail closed",
        )
    }

    @Test
    fun `config-prefix lookalike is an offender`() {
        // "tramai.security." is a config namespace, but a NEW literal beneath it
        // is not a declared property and must be treated as protocol.
        assertFalse(
            verifier.classifySourceLiteral("tramai.security.some_new_event"),
            "config-prefix lookalike must fail closed",
        )
    }

    @Test
    fun `exact declared configuration-property literals are allowed`() {
        assertTrue(verifier.classifySourceLiteral("tramai.dashboard"))
        assertTrue(verifier.classifySourceLiteral("tramai.sovereign.ops.outbox.worker"))
        assertTrue(verifier.classifySourceLiteral("tramai.security.classification"))
        assertTrue(verifier.classifySourceLiteral("tramai.providers.openai.apiKey"))
    }

    @Test
    fun `sovereign outbox protocol subtree is an offender`() {
        // The metric/tag subtree is runtime protocol even though the outbox
        // worker config prefix is a declared property.
        assertFalse(
            verifier.classifySourceLiteral("tramai.sovereign.ops.outbox.worker.failure_action"),
        )
    }
}
