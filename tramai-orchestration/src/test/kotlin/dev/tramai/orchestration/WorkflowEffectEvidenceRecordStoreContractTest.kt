package dev.tramai.orchestration

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

abstract class WorkflowEffectEvidenceRecordStoreContractTest {
    internal abstract fun createStore(): WorkflowEffectEvidenceRecordStore

    @Test
    fun `records and retrieves evidence record`() {
        runBlocking {
            val store = createStore()
            val key = WorkflowEffectEvidenceKey("wf", "run-1", "gen-1", 1L)
            val record =
                WorkflowEffectEvidenceRecord(
                    key = key,
                    stepName = "fetch",
                    stepAttemptId = "att-1",
                    effectKind = "HTTP",
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis = 1000L,
                )

            val recorded = store.recordEvidence(record)
            assertThat(recorded).isEqualTo(record)

            val loaded = store.getEvidence(key)
            assertThat(loaded).isEqualTo(record)
        }
    }

    @Test
    fun `refuses duplicate key on recordEvidence`() {
        runBlocking {
            val store = createStore()
            val key = WorkflowEffectEvidenceKey("wf", "run-1", "gen-1", 1L)
            val record =
                WorkflowEffectEvidenceRecord(
                    key = key,
                    stepName = "fetch",
                    stepAttemptId = "att-1",
                    effectKind = "HTTP",
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis = 1000L,
                )

            store.recordEvidence(record)
            try {
                store.recordEvidence(record)
                org.junit.jupiter.api
                    .fail("Expected exception on duplicate recordEvidence")
            } catch (ex: Throwable) {
                assertThat(ex is IllegalStateException || ex is WorkflowPersistenceFailureException).isTrue()
            }
        }
    }

    @Test
    fun `updates state monotonically from ADMITTED to DISPATCHED to COMPLETED`() {
        runBlocking {
            val store = createStore()
            val key = WorkflowEffectEvidenceKey("wf", "run-1", "gen-1", 1L)
            val admitted =
                WorkflowEffectEvidenceRecord(
                    key = key,
                    stepName = "fetch",
                    stepAttemptId = "att-1",
                    effectKind = "HTTP",
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis = 1000L,
                )
            store.recordEvidence(admitted)

            val dispatched = admitted.copy(state = WorkflowEffectEvidenceState.DISPATCHED)
            store.updateEvidence(dispatched)
            assertThat(store.getEvidence(key)?.state).isEqualTo(WorkflowEffectEvidenceState.DISPATCHED)

            val completed =
                dispatched.copy(
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    completedAtEpochMillis = 2000L,
                )
            store.updateEvidence(completed)
            val loaded = store.getEvidence(key)
            assertThat(loaded?.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
            assertThat(loaded?.completedAtEpochMillis).isEqualTo(2000L)
        }
    }

    @Test
    fun `forbids illegal state transition backward or from terminal state`() {
        runBlocking {
            val store = createStore()
            val key = WorkflowEffectEvidenceKey("wf", "run-1", "gen-1", 1L)
            val admitted =
                WorkflowEffectEvidenceRecord(
                    key = key,
                    stepName = "fetch",
                    stepAttemptId = "att-1",
                    effectKind = "HTTP",
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis = 1000L,
                )
            store.recordEvidence(admitted)

            // Cannot skip DISPATCHED to COMPLETED directly
            val completedDirectly =
                admitted.copy(
                    state = WorkflowEffectEvidenceState.COMPLETED,
                    completedAtEpochMillis = 2000L,
                )
            try {
                store.updateEvidence(completedDirectly)
                org.junit.jupiter.api
                    .fail("Expected exception on invalid transition")
            } catch (ex: Throwable) {
                assertThat(ex is IllegalStateException || ex is WorkflowPersistenceFailureException).isTrue()
            }

            // Move to UNCERTAIN (terminal)
            val uncertain = admitted.copy(state = WorkflowEffectEvidenceState.UNCERTAIN)
            store.updateEvidence(uncertain)

            // Cannot transition from UNCERTAIN to anything else
            val tryDispatched = uncertain.copy(state = WorkflowEffectEvidenceState.DISPATCHED)
            try {
                store.updateEvidence(tryDispatched)
                org.junit.jupiter.api
                    .fail("Expected exception on invalid transition")
            } catch (ex: Throwable) {
                assertThat(ex is IllegalStateException || ex is WorkflowPersistenceFailureException).isTrue()
            }
        }
    }

    @Test
    fun `classifies known admission sequence and reports missing sequence as unclassified`() {
        runBlocking {
            val store = createStore()
            val key = WorkflowEffectEvidenceKey("wf", "run-1", "gen-1", 10L)
            val record =
                WorkflowEffectEvidenceRecord(
                    key = key,
                    stepName = "fetch",
                    stepAttemptId = "att-1",
                    effectKind = "HTTP",
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis = 1000L,
                )
            store.recordEvidence(record)

            val classified = store.classifyAdmission("wf", "run-1", "gen-1", 10L)
            assertThat(classified).isEqualTo(WorkflowEffectClassification.Classified(record))

            // Sequence 9 (gap / missing) is strictly unclassified: never inferred as effect or not-dispatched
            val unclassified = store.classifyAdmission("wf", "run-1", "gen-1", 9L)
            assertThat(unclassified).isEqualTo(WorkflowEffectClassification.Unclassified)
        }
    }

    @Test
    fun `lists multiple evidence records sorted by admission sequence`() {
        runBlocking {
            val store = createStore()
            val r2 =
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey("wf", "run-1", "gen-1", 5L),
                    stepName = "fetch",
                    stepAttemptId = "att-1",
                    effectKind = "HTTP",
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis = 1000L,
                )
            val r1 =
                WorkflowEffectEvidenceRecord(
                    key = WorkflowEffectEvidenceKey("wf", "run-1", "gen-1", 2L),
                    stepName = "fetch",
                    stepAttemptId = "att-1",
                    effectKind = "HTTP",
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis = 900L,
                )
            store.recordEvidence(r2)
            store.recordEvidence(r1)

            val list = store.listEvidence("wf", "run-1")
            assertThat(list).containsExactly(r1, r2)
        }
    }
}

class InMemoryWorkflowEffectEvidenceRecordStoreTest : WorkflowEffectEvidenceRecordStoreContractTest() {
    override fun createStore(): WorkflowEffectEvidenceRecordStore = InMemoryWorkflowEffectEvidenceRecordStore()
}

class JdbcWorkflowEffectEvidenceRecordStoreTest : WorkflowEffectEvidenceRecordStoreContractTest() {
    private lateinit var pool: JdbcConnectionPool
    private lateinit var store: JdbcWorkflowEffectEvidenceRecordStore

    @BeforeEach
    fun setUp() {
        pool = JdbcConnectionPool.create("jdbc:h2:mem:effect_evidence_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        store = JdbcWorkflowEffectEvidenceRecordStore(pool)
        pool.connection.use { conn ->
            conn.createStatement().use { stmt ->
                store.createSchemaSql().forEach { stmt.execute(it) }
            }
        }
    }

    @AfterEach
    fun tearDown() {
        pool.dispose()
    }

    override fun createStore(): WorkflowEffectEvidenceRecordStore = store
}
