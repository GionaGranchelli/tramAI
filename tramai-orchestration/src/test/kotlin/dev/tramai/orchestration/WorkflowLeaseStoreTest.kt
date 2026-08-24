package dev.tramai.orchestration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.nio.file.Files
import java.util.logging.Logger
import javax.sql.DataSource
class WorkflowLeaseStoreTest {
    @Test
    fun `in memory lease store claims renews and releases ownership`() {
        var now = 1_000L
        val store = InMemoryWorkflowLeaseStore(clockMillis = { now })
        val claimed = runBlocking {
            store.claim(
                workflowName = "lease-workflow",
                workflowId = "wf-1",
                ownerId = "node-a",
                checkpointRevision = 3,
                leaseDurationMillis = 500,
            )
        }
        val renewed = runBlocking {
            store.renew(
                lease = claimed,
                checkpointRevision = 4,
                leaseDurationMillis = 500,
            )
        }
        assertThat(claimed.ownerId).isEqualTo("node-a")
        assertThat(claimed.checkpointRevision).isEqualTo(3)
        assertThat(renewed.checkpointRevision).isEqualTo(4)
        assertThat(renewed.expiresAtEpochMillis).isEqualTo(1_500L)
        assertThat(runBlocking { store.currentLease("lease-workflow", "wf-1") }).isEqualTo(renewed)
        runBlocking { store.release(renewed) }
        assertThat(runBlocking { store.currentLease("lease-workflow", "wf-1") }).isNull()
    }
    @Test
    fun `in memory lease store rejects competing active claims`() {
        val store = InMemoryWorkflowLeaseStore(clockMillis = { 1_000L })
        runBlocking {
            store.claim(
                workflowName = "lease-workflow",
                workflowId = "wf-1",
                ownerId = "node-a",
                checkpointRevision = 1,
                leaseDurationMillis = 500,
            )
        }
        assertThatThrownBy {
            runBlocking {
                store.claim(
                    workflowName = "lease-workflow",
                    workflowId = "wf-1",
                    ownerId = "node-b",
                    checkpointRevision = 1,
                    leaseDurationMillis = 500,
                )
            }
        }
            .isInstanceOf(WorkflowLeaseConflictException::class.java)
            .hasMessage("Workflow lease conflict")
            .hasNoCause()
    }
    @Test
    fun `in memory lease store rejects renewal after lease expiry`() {
        var now = 1_000L
        val store = InMemoryWorkflowLeaseStore(clockMillis = { now })
        val claimed = runBlocking {
            store.claim(
                workflowName = "lease-workflow",
                workflowId = "wf-expired",
                ownerId = "node-a",
                checkpointRevision = 1,
                leaseDurationMillis = 500,
            )
        }
        now = 1_600L
        assertThatThrownBy {
            runBlocking {
                store.renew(
                    lease = claimed,
                    checkpointRevision = 2,
                    leaseDurationMillis = 500,
                )
            }
        }
            .isInstanceOf(WorkflowLeaseConflictException::class.java)
            .hasMessage("Workflow lease conflict")
            .hasNoCause()
        assertThat(runBlocking { store.currentLease("lease-workflow", "wf-expired") }).isNull()
    }
    @Test
    fun `in memory lease store rejects release by the wrong owner`() {
        val store = InMemoryWorkflowLeaseStore(clockMillis = { 1_000L })
        val claimed = runBlocking {
            store.claim(
                workflowName = "lease-workflow",
                workflowId = "wf-release-conflict",
                ownerId = "node-a",
                checkpointRevision = 1,
                leaseDurationMillis = 500,
            )
        }
        assertThatThrownBy {
            runBlocking {
                store.release(
                    claimed.copy(
                        leaseId = "other-lease",
                        ownerId = "node-b",
                    ),
                )
            }
        }
            .isInstanceOf(WorkflowLeaseConflictException::class.java)
            .hasMessage("Workflow lease conflict")
            .hasNoCause()
        assertThat(runBlocking { store.currentLease("lease-workflow", "wf-release-conflict") }).isEqualTo(claimed)
    }
    @Test
    fun `file lease store claims renews and releases ownership`() {
        var now = 1_000L
        val directory = createTempDirectory("tramai-file-lease")
        try {
            val store = FileWorkflowLeaseStore(
                rootDirectory = directory,
                clockMillis = { now },
            )
            val claimed = runBlocking {
                store.claim(
                    workflowName = "lease-workflow",
                    workflowId = "wf-1",
                    ownerId = "node-a",
                    checkpointRevision = 2,
                    leaseDurationMillis = 500,
                )
            }
            val renewed = runBlocking {
                store.renew(
                    lease = claimed,
                    checkpointRevision = 3,
                    leaseDurationMillis = 750,
                )
            }
            assertThat(runBlocking { store.currentLease("lease-workflow", "wf-1") }).isEqualTo(renewed)
            assertThatThrownBy {
                runBlocking {
                    store.claim(
                        workflowName = "lease-workflow",
                        workflowId = "wf-1",
                        ownerId = "node-b",
                        checkpointRevision = 3,
                        leaseDurationMillis = 500,
                    )
                }
            }
                .isInstanceOf(WorkflowLeaseConflictException::class.java)
                .hasMessage("Workflow lease conflict")
                .hasNoCause()
            runBlocking { store.release(renewed) }
            assertThat(runBlocking { store.currentLease("lease-workflow", "wf-1") }).isNull()
        } finally {
            Files.walk(directory)
                .sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
    }
    @Test
    fun `workflow resume refuses to start when another owner holds the lease`() {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore(clockMillis = { 1_000L })
        val checkpointPersistence = WorkflowPersistence(
            checkpointStore = checkpointStore,
            stateCodec = LeaseResumeStateCodec,
            deleteCheckpointOnCompletion = false,
        )
        val workflow = workflow<LeaseResumeState>("lease-resume") {
            localStep(
                name = "finalize",
                transform = { state, _ -> state.copy(answer = "final:${state.value}") },
            )
        }.build { it.answer ?: error("answer must exist") }
        runBlocking {
            workflow.run(
                initialState = LeaseResumeState("invoice-123"),
                context = WorkflowContext(workflowId = "wf-lease"),
                persistence = checkpointPersistence,
            )
        }
        val checkpoint = runBlocking { checkpointStore.load("lease-resume", "wf-lease") }!!
        runBlocking {
            leaseStore.claim(
                workflowName = "lease-resume",
                workflowId = "wf-lease",
                ownerId = "node-a",
                checkpointRevision = checkpoint.revision,
                leaseDurationMillis = 5_000,
            )
        }
        assertThatThrownBy {
            runBlocking {
                workflow.resume(
                    context = WorkflowContext(workflowId = "wf-lease"),
                    persistence = WorkflowPersistence(
                        checkpointStore = checkpointStore,
                        stateCodec = LeaseResumeStateCodec,
                        leaseStore = leaseStore,
                        leasePolicy = WorkflowLeasePolicy(
                            ownerId = "node-b",
                            leaseDurationMillis = 5_000,
                        ),
                    ),
                )
            }
        }
            .isInstanceOf(WorkflowLeaseConflictException::class.java)
            .hasMessage("Workflow lease conflict")
            .hasNoCause()
    }
    @Test
    fun `workflow failure releases lease so another owner can take over`() {
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore(clockMillis = { 1_000L })
        val persistence = WorkflowPersistence(
            checkpointStore = checkpointStore,
            stateCodec = LeaseResumeStateCodec,
            leaseStore = leaseStore,
            leasePolicy = WorkflowLeasePolicy(
                ownerId = "node-a",
                leaseDurationMillis = 5_000,
            ),
        )
        val workflow = workflow<LeaseResumeState>("lease-failure") {
            localStep(
                name = "draft",
                transform = { state, _ -> state.copy(answer = "draft:${state.value}") },
            )
            localStep(
                name = "explode",
                transform = { _, _ -> error("boom") },
            )
        }.build { it.answer ?: error("answer must exist") }
        assertThatThrownBy {
            runBlocking {
                workflow.run(
                    initialState = LeaseResumeState("invoice-123"),
                    context = WorkflowContext(workflowId = "wf-failure"),
                    persistence = persistence,
                )
            }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("boom")
        val claimedByNextOwner = runBlocking {
            leaseStore.claim(
                workflowName = "lease-failure",
                workflowId = "wf-failure",
                ownerId = "node-b",
                checkpointRevision = checkpointStore.load("lease-failure", "wf-failure")?.revision,
                leaseDurationMillis = 5_000,
            )
        }
        assertThat(claimedByNextOwner.ownerId).isEqualTo("node-b")
    }
    @Test
    fun `jdbc lease store claims replaces expired leases renews and releases`() {
        var now = 1_000L
        val backend = FakeJdbcLeaseBackend()
        val store = JdbcWorkflowLeaseStore(
            dataSource = backend.dataSource(),
            clockMillis = { now },
        )
        val first = runBlocking {
            store.claim(
                workflowName = "jdbc-lease",
                workflowId = "wf-1",
                ownerId = "node-a",
                checkpointRevision = 1,
                leaseDurationMillis = 500,
            )
        }
        assertThat(store.createTableSql())
            .contains("CREATE TABLE")
            .contains("tramai_workflow_lease")
        assertThat(runBlocking { store.currentLease("jdbc-lease", "wf-1") }).isEqualTo(first)
        assertThatThrownBy {
            runBlocking {
                store.claim(
                    workflowName = "jdbc-lease",
                    workflowId = "wf-1",
                    ownerId = "node-b",
                    checkpointRevision = 1,
                    leaseDurationMillis = 500,
                )
            }
        }
            .isInstanceOf(WorkflowLeaseConflictException::class.java)
            .hasMessage("Workflow lease conflict")
            .hasNoCause()
        now = 2_000L
        val replacement = runBlocking {
            store.claim(
                workflowName = "jdbc-lease",
                workflowId = "wf-1",
                ownerId = "node-b",
                checkpointRevision = 2,
                leaseDurationMillis = 750,
            )
        }
        val renewed = runBlocking {
            store.renew(
                lease = replacement,
                checkpointRevision = 3,
                leaseDurationMillis = 1_000,
            )
        }
        assertThat(replacement.ownerId).isEqualTo("node-b")
        assertThat(renewed.checkpointRevision).isEqualTo(3)
        assertThat(runBlocking { store.currentLease("jdbc-lease", "wf-1") }).isEqualTo(renewed)
        runBlocking { store.release(renewed) }
        assertThat(runBlocking { store.currentLease("jdbc-lease", "wf-1") }).isNull()
    }

    @Test
    fun `file store honors an exact legacy lease and never aliases a colliding identity`() {
        var now = 1_000L
        val directory = createTempDirectory("tramai-lease-legacy")
        try {
            // Pre-collision-free lease for "order/a" written at the legacy
            // lossy path ("order_a").
            val legacyLease = WorkflowLease(
                workflowName = "order",
                workflowId = "a/b",
                leaseId = "legacy-token-1",
                ownerId = "worker-old",
                checkpointRevision = null,
                acquiredAtEpochMillis = now,
                expiresAtEpochMillis = now + 10_000,
            )
            val legacyPath = DefaultWorkflowCheckpointPathStrategy("lease.properties")
                .resolve(directory, "order", "a/b")
            Files.createDirectories(legacyPath.parent)
            Files.writeString(legacyPath, encodeLease(legacyLease))

            val store = FileWorkflowLeaseStore(directory, clockMillis = { now })
            // The exact matching legacy lease is honored (no migration).
            assertThat(runBlocking { store.currentLease("order", "a/b") }).isEqualTo(legacyLease)

            // The colliding identity must never see A's lease as its own —
            // even before it has any lease of its own.
            assertThat(runBlocking { store.currentLease("order", "a?b") }).isNull()

            // ...and can claim its canonical path independently.
            val b = runBlocking {
                store.claim("order", "a?b", "worker-new", checkpointRevision = null, leaseDurationMillis = 1_000)
            }
            assertThat(runBlocking { store.currentLease("order", "a?b") }).isEqualTo(b)
            assertThat(runBlocking { store.currentLease("order", "a?b") })
                .isNotEqualTo(legacyLease)
            assertThat(runBlocking { store.currentLease("order", "a/b") }).isEqualTo(legacyLease)

            // After expiry, the next claim for "a/b" uses the canonical path
            // and the legacy file is not authoritative anymore.
            now += 11_000
            assertThat(runBlocking { store.currentLease("order", "a/b") }).isNull()
            val again = runBlocking {
                store.claim("order", "a/b", "worker-old", checkpointRevision = null, leaseDurationMillis = 1_000)
            }
            assertThat(runBlocking { store.currentLease("order", "a/b") }).isEqualTo(again)
            assertThat(again.leaseId).isNotEqualTo("legacy-token-1")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `concurrent takeover of an expired legacy unsafe-key lease yields exactly one winner`() = runBlocking<Unit> {
        var now = 2_000L
        val directory = createTempDirectory("tramai-lease-legacy-race")
        try {
            // Expired pre-collision-free lease for "order/a" on the legacy
            // lossy path ("order_a").
            val legacyLease = WorkflowLease(
                workflowName = "order",
                workflowId = "a/b",
                leaseId = "legacy-token-9",
                ownerId = "worker-old",
                checkpointRevision = null,
                acquiredAtEpochMillis = 500,
                expiresAtEpochMillis = 1_500,
            )
            val legacyPath = DefaultWorkflowCheckpointPathStrategy("lease.properties")
                .resolve(directory, "order", "a/b")
            Files.createDirectories(legacyPath.parent)
            Files.writeString(legacyPath, encodeLease(legacyLease))

            // Deterministically park claimant A INSIDE the migration window:
            // the legacy file is already deleted, the canonical write is about
            // to move. B then runs against exactly that transient state.
            val inMigrationWindow = CompletableDeferred<Unit>()
            val releaseA = CompletableDeferred<Unit>()
            val writerA = AtomicFileWriter { _ ->
                inMigrationWindow.complete(Unit)
                runBlocking { releaseA.await() }
            }
            // B uses a SEPARATE store sharing the same directory with a plain
            // writer: its outcome must depend only on the lock namespace, not
            // on the shared hook (which would block B too and mask the race).
            val strategy = CollisionFreeWorkflowLeasePathStrategy("lease.properties")
            val storeA = FileWorkflowLeaseStore.forTest(directory, strategy, writerA) { now }
            val storeB = FileWorkflowLeaseStore.forTest(directory, strategy, AtomicFileWriter()) { now }

            val a = async(Dispatchers.Default) {
                runCatching { storeA.claim("order", "a/b", "worker-a", checkpointRevision = null, leaseDurationMillis = 1_000) }
            }
            inMigrationWindow.await()
            val b = async(Dispatchers.Default) {
                runCatching { storeB.claim("order", "a/b", "worker-b", checkpointRevision = null, leaseDurationMillis = 1_000) }
            }
            // A is provably parked mid-migration (legacy deleted, canonical
            // not yet moved) while holding the legacy lock. If B completes
            // NOW, it must have resolved the stale canonical path and
            // succeeded — the pre-fix defect. With the stable lock namespace
            // B blocks on the legacy lock instead and cannot complete.
            val bCompletedWhileABlocked = withTimeoutOrNull(2_000) { b.await() }
            releaseA.complete(Unit)
            val outcomes = listOf(a.await(), bCompletedWhileABlocked ?: b.await())

            assertThat(outcomes.count { it.isSuccess })
                .withFailMessage("expected exactly one claimant to win the expired legacy takeover, got $outcomes")
                .isEqualTo(1)
            assertThat(outcomes.filter { it.isFailure }.map { it.exceptionOrNull()?.javaClass?.name })
                .containsExactly(WorkflowLeaseConflictException::class.java.name)
            assertThat(runBlocking { storeA.currentLease("order", "a/b") }).isNotNull
            // The legacy file is gone; the single authoritative lease is the
            // canonical one.
            assertThat(Files.exists(legacyPath)).isFalse()
        } finally {
            directory.toFile().deleteRecursively()
        }
        Unit
    }

    @Test
    fun `fenced checkpoint mutation cannot overlap a successor takeover on an unsafe key`() = runBlocking<Unit> {
        var now = 1_000L
        val directory = createTempDirectory("tramai-lease-fence-takeover")
        try {
            val strategy = CollisionFreeWorkflowLeasePathStrategy("lease.properties")
            val storeA = FileWorkflowLeaseStore.forTest(directory, strategy, AtomicFileWriter()) { now }
            val storeB = FileWorkflowLeaseStore.forTest(directory, strategy, AtomicFileWriter()) { now }

            val leaseA = storeA.claim("order", "a/b", "worker-a", checkpointRevision = null, leaseDurationMillis = 10_000)
            val checkpointStore = InMemoryWorkflowCheckpointStore()
            checkpointStore.save(
                WorkflowCheckpoint("order", "a/b", nextStepIndex = 1, stepExecutions = 1, lastCompletedStepName = "step-1", statePayload = "s1", revision = 1),
            )
            val checkpointV2 = WorkflowCheckpoint("order", "a/b", nextStepIndex = 2, stepExecutions = 2, lastCompletedStepName = "step-2", statePayload = "s2", revision = 2)

            // Hooked checkpoint store: blocks INSIDE the fenced mutation, after
            // the fence validated worker A's lease and acquired the lease lock.
            val inFencedMutation = CompletableDeferred<Unit>()
            val releaseFence = CompletableDeferred<Unit>()
            val hooked = object : WorkflowCheckpointStore by checkpointStore {
                override suspend fun save(checkpoint: WorkflowCheckpoint, expectedRevision: Long?): WorkflowCheckpoint {
                    inFencedMutation.complete(Unit)
                    // Direct await (no nested runBlocking): this hook must stay
                    // cancellable so a failing test cannot strand the fence.
                    releaseFence.await()
                    return checkpointStore.save(checkpoint, expectedRevision)
                }
            }
            val fence = async(Dispatchers.Default) {
                runCatching {
                    storeA.saveCheckpointIfLeaseOwner(hooked, checkpointV2, expectedRevision = 1, expectedLease = leaseA)
                }
            }
            inFencedMutation.await()
            // Worker A's fence holds the lease lock mid-mutation. A's lease is
            // still active; now let it expire and start B's takeover.
            now = 20_000L
            val b = async(Dispatchers.Default) {
                runCatching { storeB.claim("order", "a/b", "worker-b", checkpointRevision = null, leaseDurationMillis = 10_000) }
            }
            val bCompletedWhileFenced = withTimeoutOrNull(2_000) { b.await() }
            // B must NOT complete while A's fenced checkpoint mutation holds
            // the lease lock — otherwise the stale worker A can still commit
            // state after B has taken ownership (split brain).
            assertThat(bCompletedWhileFenced)
                .withFailMessage("claim B succeeded while worker A's fenced checkpoint mutation was in flight: $bCompletedWhileFenced")
                .isNull()
            releaseFence.complete(Unit)
            assertThat(fence.await().isSuccess).isTrue()
            assertThat(b.await().isSuccess).isTrue()
            // B owns the lease afterward; A's fence completed before B's claim.
            assertThat(runBlocking { storeA.currentLease("order", "a/b")!!.ownerId }).isEqualTo("worker-b")
            assertThat(runBlocking { storeA.currentLease("order", "a/b")!!.leaseId })
                .isNotEqualTo(leaseA.leaseId)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
private data class LeaseResumeState(
    val value: String,
    val answer: String? = null,
)
private object LeaseResumeStateCodec : WorkflowStateCodec<LeaseResumeState> {
    override fun encode(state: LeaseResumeState): String = listOf(
        state.value,
        state.answer.orEmpty(),
    ).joinToString("|")
    override fun decode(payload: String): LeaseResumeState {
        val parts = payload.split("|", limit = 2)
        return LeaseResumeState(
            value = parts[0],
            answer = parts.getOrNull(1).orEmpty().ifBlank { null },
        )
    }
}
private class FakeJdbcLeaseBackend {
    private val rows = linkedMapOf<Pair<String, String>, WorkflowLease>()
    fun dataSource(): DataSource = object : DataSource {
        override fun getConnection(): Connection = connectionProxy()
        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = connectionProxy()
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getGlobal()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("Unsupported")
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }
    private fun connectionProxy(): Connection = proxyLease(Connection::class.java) { method, args ->
        when (method.name) {
            "prepareStatement" -> preparedStatementProxy(args[0] as String)
            "close", "commit", "rollback", "setAutoCommit" -> Unit
            "getAutoCommit" -> true
            "isClosed" -> false
            "unwrap" -> throw SQLException("Unsupported")
            "isWrapperFor" -> false
            else -> defaultLeaseValue(method.returnType)
        }
    }
    private fun preparedStatementProxy(sql: String): PreparedStatement {
        val normalizedSql = sql.trim().uppercase()
        val parameters = linkedMapOf<Int, Any?>()
        return proxyLease(PreparedStatement::class.java) { method, args ->
            when (method.name) {
                "setString", "setLong", "setInt", "setBoolean" -> {
                    parameters[args[0] as Int] = args[1]
                    Unit
                }
                "setNull" -> {
                    parameters[args[0] as Int] = null
                    Unit
                }
                "executeQuery" -> resultSetProxy(selectRow(parameters))
                "executeUpdate" -> executeUpdate(normalizedSql, parameters)
                "close" -> Unit
                "unwrap" -> throw SQLException("Unsupported")
                "isWrapperFor" -> false
                else -> defaultLeaseValue(method.returnType)
            }
        }
    }
    private fun selectRow(parameters: Map<Int, Any?>): WorkflowLease? = rows[
        (parameters[1] as String) to (parameters[2] as String)
    ]
    private fun executeUpdate(
        normalizedSql: String,
        parameters: Map<Int, Any?>,
    ): Int = when {
        normalizedSql.startsWith("INSERT INTO") -> {
            val lease = WorkflowLease(
                workflowName = parameters[1] as String,
                workflowId = parameters[2] as String,
                leaseId = parameters[3] as String,
                ownerId = parameters[4] as String,
                checkpointRevision = parameters[5] as Long?,
                acquiredAtEpochMillis = parameters[6] as Long,
                expiresAtEpochMillis = parameters[7] as Long,
            )
            val key = lease.workflowName to lease.workflowId
            if (rows.containsKey(key)) {
                throw SQLException("Duplicate key")
            }
            rows[key] = lease
            1
        }
        normalizedSql.startsWith("UPDATE TRAMAI_WORKFLOW_LEASE") &&
            normalizedSql.contains("LEASE_ID = ?") &&
            normalizedSql.contains("EXPIRES_AT_EPOCH_MILLIS <= ?") -> {
            val key = (parameters[6] as String) to (parameters[7] as String)
            val existing = rows[key] ?: return 0
            val now = parameters[10] as Long
            if (existing.leaseId != parameters[8] as String || existing.ownerId != parameters[9] as String) {
                return 0
            }
            if (existing.expiresAtEpochMillis > now) {
                return 0
            }
            rows[key] = existing.copy(
                leaseId = parameters[1] as String,
                ownerId = parameters[2] as String,
                checkpointRevision = parameters[3] as Long?,
                acquiredAtEpochMillis = parameters[4] as Long,
                expiresAtEpochMillis = parameters[5] as Long,
            )
            1
        }
        normalizedSql.startsWith("UPDATE TRAMAI_WORKFLOW_LEASE") &&
            normalizedSql.contains("CHECKPOINT_REVISION = ?") &&
            normalizedSql.contains("EXPIRES_AT_EPOCH_MILLIS > ?") -> {
            val key = (parameters[3] as String) to (parameters[4] as String)
            val existing = rows[key] ?: return 0
            val now = parameters[7] as Long
            if (existing.leaseId != parameters[5] as String || existing.ownerId != parameters[6] as String) {
                return 0
            }
            if (existing.expiresAtEpochMillis <= now) {
                return 0
            }
            rows[key] = existing.copy(
                checkpointRevision = parameters[1] as Long?,
                expiresAtEpochMillis = parameters[2] as Long,
            )
            1
        }
        normalizedSql.startsWith("DELETE FROM TRAMAI_WORKFLOW_LEASE") &&
            normalizedSql.contains("LEASE_ID = ?") -> {
            val key = (parameters[1] as String) to (parameters[2] as String)
            val existing = rows[key] ?: return 0
            if (existing.leaseId != parameters[3] as String || existing.ownerId != parameters[4] as String) {
                return 0
            }
            rows.remove(key)
            1
        }
        normalizedSql.startsWith("DELETE FROM TRAMAI_WORKFLOW_LEASE") &&
            normalizedSql.contains("EXPIRES_AT_EPOCH_MILLIS <= ?") -> {
            val key = (parameters[1] as String) to (parameters[2] as String)
            val existing = rows[key] ?: return 0
            val now = parameters[3] as Long
            if (existing.expiresAtEpochMillis > now) {
                return 0
            }
            rows.remove(key)
            1
        }
        else -> error("Unsupported SQL '$normalizedSql'")
    }
    private fun resultSetProxy(row: WorkflowLease?): ResultSet {
        var consumed = false
        var lastWasNull = false
        return proxyLease(ResultSet::class.java) { method, args ->
            when (method.name) {
                "next" -> if (!consumed && row != null) {
                    consumed = true
                    true
                } else {
                    false
                }
                "getString" -> {
                    lastWasNull = false
                    row?.stringLeaseValue(args[0])
                }
                "getLong" -> {
                    val value = row?.longLeaseValue(args[0])
                    lastWasNull = value == null
                    value ?: 0L
                }
                "wasNull" -> lastWasNull
                "close" -> Unit
                "unwrap" -> throw SQLException("Unsupported")
                "isWrapperFor" -> false
                else -> defaultLeaseValue(method.returnType)
            }
        }
    }
}
private fun WorkflowLease.stringLeaseValue(column: Any?): String = when (column) {
    "workflow_name" -> workflowName
    "workflow_id" -> workflowId
    "lease_id" -> leaseId
    "owner_id" -> ownerId
    else -> error("Unsupported column '$column'")
}
private fun WorkflowLease.longLeaseValue(column: Any?): Long? = when (column) {
    "checkpoint_revision" -> checkpointRevision
    "acquired_at_epoch_millis" -> acquiredAtEpochMillis
    "expires_at_epoch_millis" -> expiresAtEpochMillis
    else -> error("Unsupported column '$column'")
}
private fun <T> proxyLease(
    type: Class<T>,
    handler: (method: java.lang.reflect.Method, args: Array<out Any?>) -> Any?,
): T {
    @Suppress("UNCHECKED_CAST")
    return Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
    ) { _, method, args ->
        handler(method, args ?: emptyArray())
    } as T
}
private fun defaultLeaseValue(returnType: Class<*>): Any? = when {
    !returnType.isPrimitive -> null
    returnType == Boolean::class.javaPrimitiveType -> false
    returnType == Int::class.javaPrimitiveType -> 0
    returnType == Long::class.javaPrimitiveType -> 0L
    returnType == Short::class.javaPrimitiveType -> 0.toShort()
    returnType == Byte::class.javaPrimitiveType -> 0.toByte()
    returnType == Double::class.javaPrimitiveType -> 0.0
    returnType == Float::class.javaPrimitiveType -> 0f
    returnType == Char::class.javaPrimitiveType -> '\u0000'
    else -> null
    }
