@file:Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber", "MaxLineLength")

package dev.tramai.orchestration.testing

import dev.tramai.orchestration.APPROVAL_RESUME_EFFECT_KIND
import dev.tramai.orchestration.JdbcWorkflowEffectEvidenceRecordStore
import dev.tramai.orchestration.ParentProvenance
import dev.tramai.orchestration.WorkflowEffectEvidenceKey
import dev.tramai.orchestration.WorkflowEffectEvidenceRecord
import dev.tramai.orchestration.WorkflowEffectEvidenceState
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import javax.sql.DataSource

/**
 * Executes and qualifies the E9 durable approval-resume evidence migration and typed-parent
 * reader/writer against real PostgreSQL instances.
 *
 * Exercises the real production SQL and decoders in [JdbcWorkflowEffectEvidenceRecordStore]:
 * 1. Creates pre-E9 legacy schema (E1–E8 format with step_attempt_id NOT NULL and no parent_* columns).
 * 2. Populates legacy E1–E8 evidence rows.
 * 3. Applies Phase 1 migration SQL (adds parent_kind, parent_id, parent_version; relaxes step_attempt_id).
 * 4. Verifies pre-backfill reads fall back to step_attempt_id and construct [ParentProvenance.StepAttempt].
 * 5. Applies Phase 2 backfill SQL.
 * 6. Validates Phase 3 (zero NULL parent_id rows).
 * 7. Applies Phase 4 constraint enforcement (NOT NULL on parent_id).
 * 8. Writes an authentic E9 record with [ParentProvenance.ApprovalContinuation].
 * 9. Reads mixed legacy and E9 records, proving full data preservation and typed parent resolution.
 */
class WorkflowEffectEvidencePostgresMigrationRunner(
    private val dataSource: DataSource,
) {
    fun runMigrationAndVerification() {
        val createLegacySql =
            """
            CREATE TABLE tramai_workflow_effect_evidence (
                workflow_name VARCHAR(255) NOT NULL,
                workflow_id VARCHAR(255) NOT NULL,
                checkpoint_generation VARCHAR(255) NOT NULL,
                admission_seq BIGINT NOT NULL,
                step_name VARCHAR(255) NOT NULL,
                step_attempt_id VARCHAR(255) NOT NULL,
                effect_kind VARCHAR(64) NOT NULL,
                state VARCHAR(32) NOT NULL,
                created_at_epoch_millis BIGINT NOT NULL,
                completed_at_epoch_millis BIGINT,
                diagnostic_summary VARCHAR(1024),
                PRIMARY KEY (workflow_name, workflow_id, checkpoint_generation, admission_seq)
            )
            """.trimIndent()

        val insertLegacy =
            """
            INSERT INTO tramai_workflow_effect_evidence (
                workflow_name, workflow_id, checkpoint_generation, admission_seq,
                step_name, step_attempt_id, effect_kind, state,
                created_at_epoch_millis, completed_at_epoch_millis, diagnostic_summary
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

        // 1. Create legacy table
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute(createLegacySql) }
        }

        // 2. Pre-populate legacy E1–E8 rows
        dataSource.connection.use { conn ->
            conn.prepareStatement(insertLegacy).use { stmt ->
                stmt.setString(1, "pg-legacy-wf")
                stmt.setString(2, "run-pg-1")
                stmt.setString(3, "gen-pg-1")
                stmt.setLong(4, 1L)
                stmt.setString(5, "http_step")
                stmt.setString(6, "attempt-pg-legacy-1")
                stmt.setString(7, "HTTP_REQUEST")
                stmt.setString(8, "COMPLETED")
                stmt.setLong(9, 100_000L)
                stmt.setLong(10, 102_000L)
                stmt.setString(11, null)
                stmt.executeUpdate()
            }
        }

        val store = JdbcWorkflowEffectEvidenceRecordStore(dataSource)

        // 3. Phase 1 Schema Migration: Add parent_kind, parent_id, parent_version, relax step_attempt_id
        store.migrationPhase1Sql().forEach { sql ->
            dataSource.connection.use { conn -> conn.createStatement().use { it.execute(sql) } }
        }

        // 4. Verify older rows have NULL parent_id before backfill, and reader successfully decodes them by falling back to step_attempt_id
        runBlocking {
            val legacyKey = WorkflowEffectEvidenceKey("pg-legacy-wf", "run-pg-1", "gen-pg-1", 1L)
            val preBackfillRead = store.getEvidence(legacyKey)
            assertThat(preBackfillRead).isNotNull
            assertThat(preBackfillRead?.parent).isInstanceOf(ParentProvenance.StepAttempt::class.java)
            val stepParent = preBackfillRead?.parent as ParentProvenance.StepAttempt
            assertThat(stepParent.stepName).isEqualTo("http_step")
            assertThat(stepParent.stepAttemptId).isEqualTo("attempt-pg-legacy-1")
        }

        // 5. Phase 2: Execute Backfill SQL
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.executeUpdate(store.migrationPhase2BackfillSql()) }
        }

        // 6. Phase 3: Validate zero NULL parent_id rows exist
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM tramai_workflow_effect_evidence WHERE parent_id IS NULL").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getInt(1)).isEqualTo(0)
                }
            }
        }

        // 7. Phase 4: Enforce NOT NULL constraint on parent_id
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute(store.migrationPhase4EnforceConstraintSql()) }
        }

        // 8. Write a new E9 record into the upgraded database
        runBlocking {
            val e9Key = WorkflowEffectEvidenceKey("pg-legacy-wf", "run-pg-1", "gen-pg-1", 2L)
            val e9Record =
                WorkflowEffectEvidenceRecord(
                    key = e9Key,
                    parent =
                        ParentProvenance.ApprovalContinuation(
                            approvalId = "appr-migrated-pg-1",
                            continuationVersion = 1L,
                            toolName = "upgraded_pg_tool",
                        ),
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis = 105_000L,
                )
            store.recordEvidence(e9Record)

            // 9. Mixed reading of legacy E1–E8 and new E9
            val allRecords = store.listEvidence("pg-legacy-wf", "run-pg-1")
            assertThat(allRecords).hasSize(2)

            val rec1 = allRecords[0]
            assertThat(rec1.parent).isInstanceOf(ParentProvenance.StepAttempt::class.java)
            assertThat((rec1.parent as ParentProvenance.StepAttempt).stepAttemptId).isEqualTo("attempt-pg-legacy-1")

            val rec2 = allRecords[1]
            assertThat(rec2.parent).isInstanceOf(ParentProvenance.ApprovalContinuation::class.java)
            val approvalParent = rec2.parent as ParentProvenance.ApprovalContinuation
            assertThat(approvalParent.approvalId).isEqualTo("appr-migrated-pg-1")
            assertThat(approvalParent.continuationVersion).isEqualTo(1L)
            assertThat(approvalParent.toolName).isEqualTo("upgraded_pg_tool")
        }
    }
}
