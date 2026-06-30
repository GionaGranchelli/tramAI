package dev.tramai.core.workflow;

import dev.tramai.core.approval.gateway.ApprovalId;
import dev.tramai.core.approval.gateway.ApprovalRequestResult;
import dev.tramai.core.approval.gateway.AuditStreamId;
import dev.tramai.core.approval.gateway.HumanApprovalDecision;
import dev.tramai.core.approval.gateway.ResumeToken;
import dev.tramai.core.approval.gateway.WorkflowRunId;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import java.time.Instant;

/**
 * Java interop test for {@link ApprovalRequestResult} to {@link SovereignWorkflowResult} mapping.
 *
 * Proves that Java consumers can:
 * <ol>
 *   <li>Construct approval gateway result types via Java-friendly factories</li>
 *   <li>Call the mapper via the Java-friendly facade</li>
 *   <li>Receive all four {@link SovereignWorkflowResult} variants</li>
 *   <li>Use the decision-aware lambda from Java</li>
 *   <li>Use Java-friendly factory methods for inline value class types</li>
 *   <li>Access properties on mapped results via JvmName-annotated getters</li>
 * </ol>
 *
 * <h3>Inline value classes at JVM level</h3>
 * <p>
 * {@code @JvmInline value class} types ({@link ApprovalId}, {@link WorkflowRunId}, etc.)
 * erase to {@link String} at the JVM level. From Java:
 * <ul>
 *   <li>Construct via factory: {@code ApprovalIds.of("value")} (returns boxed type)</li>
 *   <li>Read from data class getters as String: {@code suspended.getApprovalId()}</li>
 *   <li>Pass clean Strings to factory methods that accept inline types internally</li>
 * </ul>
 */
class ApprovalRequestWorkflowResultMappersJavaInteropTest {

    private static final Instant NOW = Instant.parse("2026-06-25T10:00:00Z");

    // ── AlreadyApproved → Completed ──

    @Test
    void mapsAlreadyApprovedFromJava() {
        var decision = HumanApprovalDecisions.approved(
            "approval-1", "reviewer-1", NOW, "approved"
        );

        SovereignWorkflowResult<String> result =
            ApprovalWorkflowResults.fromApprovalRequestResult(
                ApprovalRequestResults.alreadyApproved(decision),
                approved -> approved.getDecidedBy() + ":" + approved.getComment()
            );

        Assertions.assertThat(result).isInstanceOf(SovereignWorkflowResult.Completed.class);
        Assertions.assertThat(((SovereignWorkflowResult.Completed<?>) result).getValue())
            .isEqualTo("reviewer-1:approved");
    }

    // ── Suspended → SuspendedForApproval ──

    @Test
    void mapsSuspendedFromJava() {
        SovereignWorkflowResult<String> result =
            ApprovalWorkflowResults.fromApprovalRequestResult(
                ApprovalRequestResults.suspended(
                    "approval-1", "run-1", "audit-1", "token-1"
                ),
                approved -> { throw new AssertionError("should not run"); }
            );

        Assertions.assertThat(result).isInstanceOf(SovereignWorkflowResult.SuspendedForApproval.class);
        var suspended = (SovereignWorkflowResult.SuspendedForApproval) result;
        // Inline value class types erase to String at JVM level
        Assertions.assertThat(suspended.getApprovalId()).isEqualTo("approval-1");
        Assertions.assertThat(suspended.getWorkflowRunId()).isEqualTo("run-1");
        Assertions.assertThat(suspended.getAuditStreamId()).isEqualTo("audit-1");
        Assertions.assertThat(suspended.getResumeToken()).isEqualTo("token-1");
    }

    // ── AlreadyDenied → Rejected ──

    @Test
    void mapsDeniedFromJava() {
        var decision = HumanApprovalDecisions.denied(
            "approval-1", "reviewer-1", NOW, "not enough evidence", null
        );

        SovereignWorkflowResult<String> result =
            ApprovalWorkflowResults.fromApprovalRequestResult(
                ApprovalRequestResults.alreadyDenied(decision),
                approved -> { throw new AssertionError("should not run"); }
            );

        Assertions.assertThat(result).isInstanceOf(SovereignWorkflowResult.Rejected.class);
        Assertions.assertThat(((SovereignWorkflowResult.Rejected) result).getReason())
            .isEqualTo("not enough evidence");
    }

    // ── Expired → Expired ──

    @Test
    void mapsExpiredFromJava() {
        SovereignWorkflowResult<String> result =
            ApprovalWorkflowResults.fromApprovalRequestResult(
                ApprovalRequestResults.expired("approval-1", NOW, "expired"),
                approved -> { throw new AssertionError("should not run"); }
            );

        Assertions.assertThat(result).isInstanceOf(SovereignWorkflowResult.Expired.class);
        Assertions.assertThat(((SovereignWorkflowResult.Expired) result).getReason())
            .isEqualTo("expired");
    }

    // ── Value type factory methods ──
    //
    // NOTE: ApprovalIds.of("..."), WorkflowRunIds.of("...") etc. are NOT
    // callable from Java because Kotlin JVM name mangles methods that
    // return inline value class types. These factories exist for Kotlin
    // code that wants explicit construction.
    //
    // Java users pass plain Strings to ApprovalRequestResults.suspended()
    // and HumanApprovalDecisions.approved() — those methods accept String
    // parameters and construct the inline types internally.

    @Test
    void javaPassesPlainStringsForInlineValueTypes() {
        // Java does not construct or receive inline value class types directly.
        // All factory methods that accept inline types use String parameters,
        // and SuspendedForApproval getters delegate to the JVM underlying type.
        var suspended = ApprovalRequestResults.suspended("a", "b", "c", "d");
        Assertions.assertThat(suspended).isInstanceOf(ApprovalRequestResult.Suspended.class);

        var approval = HumanApprovalDecisions.approved("a", "reviewer", NOW, null);
        var approved = ApprovalRequestResults.alreadyApproved(approval);
        Assertions.assertThat(approved).isInstanceOf(ApprovalRequestResult.AlreadyApproved.class);

        var denial = HumanApprovalDecisions.denied("a", "reviewer", NOW, "reason", null);
        var denied = ApprovalRequestResults.alreadyDenied(denial);
        Assertions.assertThat(denied).isInstanceOf(ApprovalRequestResult.AlreadyDenied.class);

        var expired = ApprovalRequestResults.expired("a", NOW, "expired");
        Assertions.assertThat(expired).isInstanceOf(ApprovalRequestResult.Expired.class);
    }

    // ── Lambda not invoked for terminal states ──

    @Test
    void lambdaNotInvokedForSuspended() {
        ApprovalWorkflowResults.fromApprovalRequestResult(
            ApprovalRequestResults.suspended("a", "b", "c", "d"),
            approved -> { throw new AssertionError("should not run"); }
        );
    }

    @Test
    void lambdaNotInvokedForDenied() {
        var decision = HumanApprovalDecisions.denied("a", "reviewer", NOW, "reason", null);
        ApprovalWorkflowResults.fromApprovalRequestResult(
            ApprovalRequestResults.alreadyDenied(decision),
            approved -> { throw new AssertionError("should not run"); }
        );
    }

    @Test
    void lambdaNotInvokedForExpired() {
        ApprovalWorkflowResults.fromApprovalRequestResult(
            ApprovalRequestResults.expired("a", NOW, "expired"),
            approved -> { throw new AssertionError("should not run"); }
        );
    }
}
