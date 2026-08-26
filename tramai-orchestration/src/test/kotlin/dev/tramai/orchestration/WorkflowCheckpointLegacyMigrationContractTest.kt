package dev.tramai.orchestration

import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Base64
import java.util.Properties
import javax.sql.DataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Epic 8.2f legacy checkpoint migration mini-contract.
 *
 * A checkpoint persisted before 8.2f carries no generation token: the
 * [WorkflowCheckpoint.checkpointGeneration] key is absent from the physical
 * File/Markdown record, and the JDBC row has a NULL `checkpoint_generation`
 * column. The contract under test:
 *
 * 1. **Legacy load** — a physically old record loads with
 *    `checkpointGeneration == null` and its revision preserved.
 * 2. **First fenced write installs the token** — the FIRST fenced
 *    mutation (save/requireRecovery/clearRecovery/delete with
 *    `expectedGeneration = null` against the null-generation record)
 *    succeeds and atomically mints a real token; every subsequent fenced
 *    operation must use that captured generation and succeeds.
 * 3. **Stale legacy writer conflicts** — a second writer still holding the
 *    pre-migration capability (null generation, same `expectedRevision`)
 *    conflicts, and the record stays value-identical (token unchanged).
 * 4. **Concurrent migration race** — two coroutines capturing the same
 *    legacy (null generation, revision 1) capability race a fenced save;
 *    exactly one installs the token, the other throws
 *    [WorkflowCheckpointConflictException], and the final record is
 *    revision 2 with a single generation.
 * 5. **Legacy resume** — a null-generation checkpoint resumed through
 *    [Workflow.resume] installs the token on the first save; the
 *    [WorkflowPersistenceSession] updates its internal generation so the
 *    completion delete is generation-fenced and no conflict escapes.
 *
 * The legacy physical formats are constructed directly (properties file
 * without the `checkpointGeneration` key, Markdown front matter without the
 * `checkpointGeneration` line, JDBC row with NULL column) — exactly the
 * bytes/rows a pre-8.2f process would have left behind.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowCheckpointLegacyMigrationContractTest {

    private companion object {
        const val JDBC_URL = "jdbc:h2:mem:legacy_migration;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
    }

    private lateinit var dataSource: DataSource
    private val tempRoots = mutableListOf<Path>()

    @BeforeAll
    fun setUpAll() {
        val ds = JdbcDataSource()
        ds.setURL(JDBC_URL)
        ds.user = "sa"
        ds.password = ""
        dataSource = ds
        DriverManager.getConnection(JDBC_URL, "sa", "").use { conn ->
            conn.createStatement().use { it.execute(JdbcWorkflowCheckpointStore(dataSource).createTableSql()) }
        }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching {
            DriverManager.getConnection(JDBC_URL, "sa", "").use { conn ->
                conn.createStatement().use { it.execute("DROP TABLE IF EXISTS tramai_workflow_checkpoint") }
            }
        }
        tempRoots.forEach { root -> runCatching { root.toFile().deleteRecursively() } }
    }

    private data class State(val request: String, val draft: String? = null, val finalAnswer: String? = null)

    private object StateCodec : WorkflowStateCodec<State> {
        override fun encode(state: State): String = "${state.request}|${state.draft}|${state.finalAnswer}"
        override fun decode(payload: String): State {
            val parts = payload.split("|")
            return State(
                request = parts.getOrElse(0) { "" },
                draft = parts.getOrElse(1) { "" }.ifBlank { null },
                finalAnswer = parts.getOrElse(2) { "" }.ifBlank { null },
            )
        }
    }

    private class LegacyStore(
        val label: String,
        val store: WorkflowCheckpointStore,
        val seed: (WorkflowCheckpoint) -> Unit,
    )

    /** A pre-8.2f record: every field except a generation token. */
    private fun legacyCheckpoint(
        workflowName: String,
        workflowId: String,
        revision: Long,
        nextStepIndex: Int = 2,
        stepExecutions: Int = 3,
        lastCompletedStepName: String? = "step-one",
        statePayload: String = """{"state":"legacy"}""",
        metadata: Map<String, String> = emptyMap(),
    ): WorkflowCheckpoint = WorkflowCheckpoint(
        workflowName = workflowName,
        workflowId = workflowId,
        nextStepIndex = nextStepIndex,
        stepExecutions = stepExecutions,
        lastCompletedStepName = lastCompletedStepName,
        statePayload = statePayload,
        revision = revision,
        metadata = metadata,
        savedAtEpochMillis = 1_000L,
        recoveryState = WorkflowRecoveryState.Normal,
        checkpointGeneration = null,
    )

    /** Fresh File, Markdown and JDBC stores, each seeded on demand. */
    private fun freshLegacyStores(): List<LegacyStore> {
        val fileRoot = Files.createTempDirectory("legacy-migration-file-").toAbsolutePath()
        val markdownRoot = Files.createTempDirectory("legacy-migration-md-").toAbsolutePath()
        tempRoots += listOf(fileRoot, markdownRoot)
        DriverManager.getConnection(JDBC_URL, "sa", "").use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM tramai_workflow_checkpoint") }
        }
        return listOf(
            LegacyStore("file", FileWorkflowCheckpointStore(fileRoot)) { seedLegacyFile(fileRoot, it) },
            LegacyStore("markdown", MarkdownWorkflowCheckpointStore(markdownRoot)) { seedLegacyMarkdown(markdownRoot, it) },
            LegacyStore("jdbc", JdbcWorkflowCheckpointStore(dataSource)) { seedLegacyJdbc(it) },
        )
    }

    /** Old-format properties file: no `checkpointGeneration` key. */
    private fun seedLegacyFile(root: Path, checkpoint: WorkflowCheckpoint) {
        val path = root.resolve(urlSegment(checkpoint.workflowName))
            .resolve(urlSegment(checkpoint.workflowId))
            .resolve("checkpoint.properties")
        Files.createDirectories(path.parent)
        val properties = Properties()
        properties["workflowName"] = checkpoint.workflowName
        properties["workflowId"] = checkpoint.workflowId
        properties["nextStepIndex"] = checkpoint.nextStepIndex.toString()
        properties["stepExecutions"] = checkpoint.stepExecutions.toString()
        properties["lastCompletedStepName"] = checkpoint.lastCompletedStepName.orEmpty()
        properties["statePayloadBase64"] = base64Encode(checkpoint.statePayload)
        properties["revision"] = checkpoint.revision.toString()
        properties["savedAtEpochMillis"] = checkpoint.savedAtEpochMillis.toString()
        properties["recoveryState"] = encodeRecoveryState(checkpoint.recoveryState)?.let(::base64Encode).orEmpty()
        checkpoint.metadata.forEach { (key, value) ->
            properties["metadata.${base64Encode(key)}"] = base64Encode(value)
        }
        Files.writeString(
            path,
            StringWriter().also { properties.store(it, "Tramai workflow checkpoint") }.toString(),
        )
    }

    /** Old-format Markdown: no `checkpointGeneration` front-matter line. */
    private fun seedLegacyMarkdown(root: Path, checkpoint: WorkflowCheckpoint) {
        val path = root.resolve(urlSegment(checkpoint.workflowName))
            .resolve(urlSegment(checkpoint.workflowId))
            .resolve("checkpoint.md")
        Files.createDirectories(path.parent)
        val metadataLines = checkpoint.metadata.entries
            .sortedBy { it.key }
            .joinToString("\n") { "metadata.${base64Encode(it.key)}: ${base64Encode(it.value)}" }
        val content = buildString {
            appendLine("---")
            appendLine("workflowName: ${base64Encode(checkpoint.workflowName)}")
            appendLine("workflowId: ${base64Encode(checkpoint.workflowId)}")
            appendLine("nextStepIndex: ${checkpoint.nextStepIndex}")
            appendLine("stepExecutions: ${checkpoint.stepExecutions}")
            appendLine("lastCompletedStepName: ${base64Encode(checkpoint.lastCompletedStepName.orEmpty())}")
            appendLine("revision: ${checkpoint.revision}")
            appendLine("savedAtEpochMillis: ${checkpoint.savedAtEpochMillis}")
            appendLine("recoveryState: ${encodeRecoveryState(checkpoint.recoveryState)?.let(::base64Encode).orEmpty()}")
            if (metadataLines.isNotBlank()) {
                appendLine(metadataLines)
            }
            appendLine("---")
            appendLine("# Tramai Workflow Checkpoint")
            appendLine()
            appendLine("## State Payload")
            appendLine()
            appendLine("``` text")
            appendLine(checkpoint.statePayload)
            appendLine("```")
        }
        Files.writeString(path, content)
    }

    /** Old-format JDBC row: `checkpoint_generation` left NULL. */
    private fun seedLegacyJdbc(checkpoint: WorkflowCheckpoint) {
        DriverManager.getConnection(JDBC_URL, "sa", "").use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tramai_workflow_checkpoint (
                    workflow_name, workflow_id, next_step_index, step_executions,
                    last_completed_step_name, state_payload, revision, metadata_payload,
                    saved_at_epoch_millis, recovery_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, checkpoint.workflowName)
                statement.setString(2, checkpoint.workflowId)
                statement.setInt(3, checkpoint.nextStepIndex)
                statement.setInt(4, checkpoint.stepExecutions)
                statement.setString(5, checkpoint.lastCompletedStepName)
                statement.setString(6, checkpoint.statePayload)
                statement.setLong(7, checkpoint.revision)
                statement.setString(8, encodeMetadata(checkpoint.metadata))
                statement.setLong(9, checkpoint.savedAtEpochMillis)
                statement.setString(10, encodeRecoveryState(checkpoint.recoveryState))
                statement.executeUpdate()
            }
        }
    }

    private fun urlSegment(input: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(input.toByteArray(StandardCharsets.UTF_8))

    private suspend fun <T, R> runInParallel(
        items: List<T>,
        block: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val ready = Channel<Unit>(items.size)
        val release = CompletableDeferred<Unit>()
        val workers = items.map { item ->
            async(Dispatchers.Default) {
                ready.send(Unit)
                release.await()
                block(item)
            }
        }
        repeat(items.size) { ready.receive() }
        release.complete(Unit)
        workers.map { it.await() }
    }

    private fun recoveryRecord(attemptId: String = "attempt-1") = WorkflowRecoveryRecord(
        reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
        stepName = "step-one",
        attemptId = attemptId,
        priorWorkerId = "worker-1",
        detectedAtEpochMillis = 1_000L,
    )

    // ── 1. Legacy load ──────────────────────────────────────────────

    @Test
    fun `legacy record without generation key loads with null generation and preserved revision`() = runBlocking<Unit> {
        freshLegacyStores().forEach { fixture ->
            val legacy = legacyCheckpoint("legacy-load", "${fixture.label}-wf", revision = 7, metadata = mapOf("region" to "eu-west"))
            fixture.seed(legacy)

            val loaded = fixture.store.load("legacy-load", "${fixture.label}-wf")

            assertThat(loaded).withFailMessage("${fixture.label}: legacy record must load").isNotNull()
            loaded!!
            assertThat(loaded.checkpointGeneration)
                .withFailMessage("${fixture.label}: pre-8.2f record must load with null generation")
                .isNull()
            assertThat(loaded.revision)
                .withFailMessage("${fixture.label}: legacy revision must be preserved")
                .isEqualTo(7)
            assertThat(loaded.nextStepIndex).isEqualTo(2)
            assertThat(loaded.stepExecutions).isEqualTo(3)
            assertThat(loaded.lastCompletedStepName).isEqualTo("step-one")
            assertThat(loaded.statePayload).isEqualTo("""{"state":"legacy"}""")
            assertThat(loaded.metadata).isEqualTo(mapOf("region" to "eu-west"))
            assertThat(loaded.recoveryState).isEqualTo(WorkflowRecoveryState.Normal)
        }
    }

    // ── 2. First fenced write installs the token ───────────────────

    @Test
    fun `first fenced save on a legacy record mints a token and later fenced operations succeed`() = runBlocking<Unit> {
        freshLegacyStores().forEach { fixture ->
            val name = "legacy-migrate"
            val id = "${fixture.label}-wf"
            fixture.seed(legacyCheckpoint(name, id, revision = 7))

            // The first fenced mutation carries the legacy capability
            // (null generation, expectedRevision = 7) and must succeed,
            // atomically installing a real token.
            val migrated = fixture.store.save(
                legacyCheckpoint(name, id, revision = 7).copy(
                    nextStepIndex = 4,
                    statePayload = "migrated",
                    checkpointGeneration = null,
                ),
                expectedRevision = 7,
            )
            assertThat(migrated.revision).isEqualTo(8)
            assertThat(migrated.checkpointGeneration)
                .withFailMessage("${fixture.label}: first fenced write must mint a generation token")
                .isNotNull()
            val generation = migrated.checkpointGeneration!!

            // Subsequent fenced operations use the captured generation.
            val second = fixture.store.save(
                migrated.copy(nextStepIndex = 5, statePayload = "second", checkpointGeneration = generation),
                expectedRevision = 8,
            )
            assertThat(second.revision).isEqualTo(9)
            assertThat(second.checkpointGeneration).isEqualTo(generation)

            val required = fixture.store.requireRecovery(
                name,
                id,
                expectedRevision = 9,
                record = recoveryRecord("attempt-migrate"),
                expectedGeneration = generation,
            )
            assertThat(required.revision).isEqualTo(10)
            assertThat(required.checkpointGeneration).isEqualTo(generation)
            assertThat(required.recoveryState).isEqualTo(WorkflowRecoveryState.Required(recoveryRecord("attempt-migrate")))

            val cleared = fixture.store.clearRecovery(name, id, expectedRevision = 10, expectedGeneration = generation)
            assertThat(cleared.revision).isEqualTo(11)
            assertThat(cleared.checkpointGeneration).isEqualTo(generation)
            assertThat(cleared.recoveryState).isEqualTo(WorkflowRecoveryState.Normal)

            fixture.store.delete(name, id, expectedRevision = 11, expectedGeneration = generation)
            assertThat(fixture.store.load(name, id)).isNull()
        }
    }

    // ── 3. Stale legacy writer conflicts ────────────────────────────

    @Test
    fun `stale legacy writer conflicts after token installation and the record stays value-identical`() = runBlocking<Unit> {
        freshLegacyStores().forEach { fixture ->
            val name = "legacy-stale"
            val id = "${fixture.label}-wf"
            fixture.seed(legacyCheckpoint(name, id, revision = 7))

            // Writer A: the first fenced write from legacy state mints the token.
            val winner = fixture.store.save(
                legacyCheckpoint(name, id, revision = 7).copy(nextStepIndex = 4, checkpointGeneration = null),
                expectedRevision = 7,
            )
            assertThat(winner.checkpointGeneration).isNotNull()

            // Writer B: a second legacy writer still holding the pre-migration
            // capability (null generation, same expectedRevision = 7) must conflict.
            val stale = runCatching {
                fixture.store.save(
                    legacyCheckpoint(name, id, revision = 7).copy(nextStepIndex = 9, checkpointGeneration = null),
                    expectedRevision = 7,
                )
            }.exceptionOrNull()
            assertThat(stale)
                .withFailMessage("${fixture.label}: stale legacy writer must conflict")
                .isInstanceOf(WorkflowCheckpointConflictException::class.java)
            assertThat(fixture.store.load(name, id)).isEqualTo(winner)

            // Writer C: correct revision but no token — the generation fence
            // alone must reject the null-generation write.
            val ungenerated = runCatching {
                fixture.store.save(
                    winner.copy(nextStepIndex = 10, checkpointGeneration = null),
                    expectedRevision = winner.revision,
                )
            }.exceptionOrNull()
            assertThat(ungenerated)
                .withFailMessage("${fixture.label}: null generation at current revision must conflict")
                .isInstanceOf(WorkflowCheckpointConflictException::class.java)
            assertThat(fixture.store.load(name, id)).isEqualTo(winner)
        }
    }

    // ── 4. Concurrent migration race ────────────────────────────────

    @Test
    fun `concurrent legacy migration race installs exactly one token at revision 2`() = runBlocking<Unit> {
        repeat(8) { round ->
            freshLegacyStores().forEach { fixture ->
                val name = "legacy-race"
                val id = "${fixture.label}-$round"
                fixture.seed(legacyCheckpoint(name, id, revision = 1, nextStepIndex = 0))
                val payloads = listOf("worker-a", "worker-b")

                // Both writers capture the same legacy capability
                // (null generation, revision 1) and race a fenced save.
                val outcomes = runInParallel(payloads) { payload ->
                    runCatching {
                        fixture.store.save(
                            legacyCheckpoint(name, id, revision = 1, nextStepIndex = 0)
                                .copy(statePayload = payload, checkpointGeneration = null),
                            expectedRevision = 1,
                        )
                    }
                }

                val successes = outcomes.count { it.isSuccess }
                assertThat(successes).withFailMessage {
                    "${fixture.label} round $round: expected exactly one migration winner, got $successes"
                }.isEqualTo(1)
                assertThat(outcomes.filter { it.isFailure }.map { it.exceptionOrNull()?.javaClass?.name }.toSet())
                    .withFailMessage("${fixture.label} round $round: loser must be a conflict")
                    .containsExactly(WorkflowCheckpointConflictException::class.java.name)

                val final = fixture.store.load(name, id)!!
                assertThat(final.revision).isEqualTo(2)
                assertThat(final.checkpointGeneration)
                    .withFailMessage("${fixture.label} round $round: exactly one token must be installed")
                    .isNotNull()
                val winnerIndex = outcomes.indexOfFirst { it.isSuccess }
                assertThat(final.statePayload).isEqualTo(payloads[winnerIndex])
                assertThat(final.checkpointGeneration)
                    .isEqualTo(outcomes[winnerIndex].getOrNull()?.checkpointGeneration)
            }
        }
    }

    // ── 5. Legacy resume ────────────────────────────────────────────

    @Test
    fun `legacy resume installs a token on the first save and the completion delete is generation-fenced`() = runBlocking<Unit> {
        freshLegacyStores().forEach { fixture ->
            val context = WorkflowContext(workflowId = "${fixture.label}-wf")
            val workflow = workflow<State>("legacy-resume") {
                localStep("step-one") { state, _ -> state.copy(draft = "after-one") }
                localStep("step-two") { state, _ -> state.copy(finalAnswer = "${state.draft}-two") }
            }.build { it.finalAnswer }
            val persistence = WorkflowPersistence(checkpointStore = fixture.store, stateCodec = StateCodec)

            // A legacy checkpoint suspended mid-run: definition metadata
            // present (as 8.1f wrote it), no generation token, revision 1.
            fixture.seed(
                WorkflowCheckpoint(
                    workflowName = workflow.name,
                    workflowId = context.workflowId,
                    nextStepIndex = 1,
                    stepExecutions = 1,
                    lastCompletedStepName = "step-one",
                    statePayload = StateCodec.encode(State(request = "r", draft = "after-one")),
                    revision = 1,
                    metadata = workflow.checkpointMetadata(),
                    savedAtEpochMillis = 1_000L,
                    recoveryState = WorkflowRecoveryState.Normal,
                    checkpointGeneration = null,
                ),
            )

            val result = workflow.resume(context = context, persistence = persistence)

            assertThat(result).isEqualTo("after-one-two")
            // The first saveCheckpoint minted the token, the session adopted
            // it, and the completion delete used it: no checkpoint remains
            // and no conflict escaped.
            assertThat(fixture.store.load(workflow.name, context.workflowId)).isNull()
        }
    }
}
