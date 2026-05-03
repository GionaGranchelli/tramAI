package dev.tramai.scheduler

import dev.tramai.orchestration.NoOpWorkflowObserver
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import dev.tramai.orchestration.WorkflowScheduleDefinition
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC-backed scheduler store.
 *
 * Applications supply the pooled DataSource, typically a HikariCP DataSource,
 * and may use [createTableSql] to install the required tables.
 */
class JdbcWorkflowSchedulerStore(
    private val dataSource: DataSource,
    private val observer: WorkflowObserver = NoOpWorkflowObserver,
) : WorkflowSchedulerStore {
    override suspend fun upsertSchedule(schedule: ScheduleRecord) {
        val cronSchedule = cronSchedule(schedule.schedule, "JdbcWorkflowSchedulerStore")
        transaction { connection ->
            connection.prepareStatement(
                upsertScheduleSql(connection),
            ).use { statement ->
                statement.setString(1, schedule.scheduleId)
                statement.setString(2, schedule.workflowName)
                statement.setString(3, cronSchedule.expression)
                statement.setString(4, cronSchedule.zoneId.id)
                statement.setTimestamp(5, timestamp(schedule.nextFireAt))
                statement.setBoolean(6, schedule.enabled)
                statement.setString(7, encodeCalendarRules(schedule.skipCalendar))
                statement.setBoolean(8, schedule.businessHoursOnly)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun getSchedule(scheduleId: String): ScheduleRecord? =
        transaction { connection ->
            connection.prepareStatement(
                """
                SELECT schedule_id, workflow_name, cron_expression, timezone, next_fire_at, enabled, skip_calendar, business_hours_only
                FROM workflow_schedules
                WHERE schedule_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, scheduleId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        null
                    } else {
                        resultSet.toScheduleRecord()
                    }
                }
            }
        }

    override suspend fun claimDueTicks(
        now: Instant,
        ownerId: String,
        claimDuration: Duration,
        limit: Int,
    ): List<ClaimedScheduledTick> {
        require(ownerId.isNotBlank()) { "WorkflowSchedulerStore.claimDueTicks ownerId must not be blank" }
        require(limit > 0) { "WorkflowSchedulerStore.claimDueTicks limit must be greater than zero" }
        require(!claimDuration.isNegative && !claimDuration.isZero) {
            "WorkflowSchedulerStore.claimDueTicks claimDuration must be positive"
        }
        val claimExpiresAt = now.plus(claimDuration)
        return transaction { connection ->
            val claimed = mutableListOf<ClaimedScheduledTick>()
            claimed += reclaimExpiredTicks(
                connection = connection,
                now = now,
                ownerId = ownerId,
                claimExpiresAt = claimExpiresAt,
                limit = limit,
            )
            if (claimed.size < limit) {
                claimed += createAndClaimDueTicks(
                    connection = connection,
                    now = now,
                    ownerId = ownerId,
                    claimExpiresAt = claimExpiresAt,
                    limit = limit - claimed.size,
                )
            }
            claimed
        }
    }

    override suspend fun markTickStarted(
        tickId: String,
        claimToken: String,
        runId: String,
    ) {
        updateClaimedTick(
            tickId = tickId,
            claimToken = claimToken,
            terminalAction = "start",
            sql = """
                UPDATE workflow_schedule_ticks
                SET status = 'STARTED',
                    workflow_run_id = ?
                WHERE tick_id = ?
                    AND claim_token = ?
                    AND status IN ('CLAIMED', 'STARTED')
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, runId)
            statement.setString(2, tickId)
            statement.setString(3, claimToken)
        }
    }

    /*
     * Released ticks keep status='CLAIMED' because CLAIMED is the non-terminal
     * state scanned by reclaimExpiredTicks. A row with claim_token=NULL and
     * claim_expires_at=EPOCH is logically released and immediately eligible
     * for another owner to reclaim.
     */
    override suspend fun releaseTickClaim(
        tickId: String,
        claimToken: String,
    ) {
        updateClaimedTick(
            tickId = tickId,
            claimToken = claimToken,
            terminalAction = "release",
            sql = """
                UPDATE workflow_schedule_ticks
                SET status = 'CLAIMED',
                    owner_id = NULL,
                    claim_token = NULL,
                    claim_expires_at = ?,
                    workflow_run_id = NULL
                WHERE tick_id = ?
                    AND claim_token = ?
                    AND status IN ('CLAIMED', 'STARTED')
            """.trimIndent(),
        ) { statement ->
            statement.setTimestamp(1, timestamp(Instant.EPOCH))
            statement.setString(2, tickId)
            statement.setString(3, claimToken)
        }
    }

    override suspend fun markTickCompleted(
        tickId: String,
        claimToken: String,
    ) {
        markTerminalTick(tickId, claimToken, "COMPLETED", null)
    }

    override suspend fun markTickSkipped(
        tickId: String,
        claimToken: String,
        reason: String,
    ) {
        markTerminalTick(tickId, claimToken, "SKIPPED", reason)
    }

    override suspend fun markTickMisfired(
        tickId: String,
        claimToken: String,
        reason: String,
    ) {
        markTerminalTick(tickId, claimToken, "MISFIRED", reason)
    }

    override suspend fun scheduleDelayWakeup(
        runId: String,
        stepId: String,
        resumeAt: Instant,
    ) {
        require(runId.isNotBlank()) { "Delay wakeup runId must not be blank" }
        require(stepId.isNotBlank()) { "Delay wakeup stepId must not be blank" }
        transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE workflow_delay_wakeups
                SET resume_at = ?,
                    status = 'PENDING',
                    owner_id = NULL,
                    claim_token = NULL,
                    claim_expires_at = NULL
                WHERE run_id = ?
                    AND step_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setTimestamp(1, timestamp(resumeAt))
                statement.setString(2, runId)
                statement.setString(3, stepId)
                if (statement.executeUpdate() > 0) {
                    return@transaction
                }
            }
            connection.prepareStatement(
                """
                INSERT INTO workflow_delay_wakeups (
                    run_id,
                    step_id,
                    resume_at,
                    status,
                    owner_id,
                    claim_token,
                    claim_expires_at
                ) VALUES (?, ?, ?, 'PENDING', NULL, NULL, NULL)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, runId)
                statement.setString(2, stepId)
                statement.setTimestamp(3, timestamp(resumeAt))
                statement.executeUpdate()
            }
        }
    }

    override suspend fun claimDueDelayWakeups(
        now: Instant,
        ownerId: String,
        claimDuration: Duration,
        limit: Int,
    ): List<ClaimedDelayWakeup> {
        require(ownerId.isNotBlank()) { "Delay wakeup ownerId must not be blank" }
        require(limit > 0) { "WorkflowSchedulerStore.claimDueDelayWakeups limit must be greater than zero" }
        require(!claimDuration.isNegative && !claimDuration.isZero) {
            "WorkflowSchedulerStore.claimDueDelayWakeups claimDuration must be positive"
        }
        val claimExpiresAt = now.plus(claimDuration)
        return transaction { connection ->
            connection.prepareStatement(
                """
                SELECT run_id, step_id, resume_at
                FROM workflow_delay_wakeups
                WHERE resume_at <= ?
                    AND (
                        status = 'PENDING'
                        OR (status = 'CLAIMED' AND claim_expires_at <= ?)
                    )
                ORDER BY resume_at, run_id, step_id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """.trimIndent(),
            ).use { statement ->
                statement.setTimestamp(1, timestamp(now))
                statement.setTimestamp(2, timestamp(now))
                statement.setInt(3, limit)
                statement.executeQuery().use { resultSet ->
                    val wakeups = mutableListOf<ClaimedDelayWakeup>()
                    while (resultSet.next()) {
                        val runId = resultSet.getString("run_id")
                        val stepId = resultSet.getString("step_id")
                        val resumeAt = resultSet.instant("resume_at")
                        val claimToken = UUID.randomUUID().toString()
                        updateDelayWakeupClaim(
                            connection = connection,
                            runId = runId,
                            stepId = stepId,
                            ownerId = ownerId,
                            claimToken = claimToken,
                            claimExpiresAt = claimExpiresAt,
                        )
                        wakeups += ClaimedDelayWakeup(
                            runId = runId,
                            stepId = stepId,
                            resumeAt = resumeAt,
                            claimToken = claimToken,
                            claimExpiresAt = claimExpiresAt,
                        )
                    }
                    wakeups
                }
            }
        }
    }

    override suspend fun releaseDelayWakeupClaim(
        runId: String,
        stepId: String,
        claimToken: String,
    ) {
        updateClaimedDelayWakeup(
            runId = runId,
            stepId = stepId,
            claimToken = claimToken,
            action = "release",
            sql = """
                UPDATE workflow_delay_wakeups
                SET status = 'PENDING',
                    owner_id = NULL,
                    claim_token = NULL,
                    claim_expires_at = NULL
                WHERE run_id = ?
                    AND step_id = ?
                    AND claim_token = ?
                    AND status = 'CLAIMED'
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, runId)
            statement.setString(2, stepId)
            statement.setString(3, claimToken)
        }
    }

    override suspend fun markDelayWakeupCompleted(
        runId: String,
        stepId: String,
        claimToken: String,
    ) {
        updateClaimedDelayWakeup(
            runId = runId,
            stepId = stepId,
            claimToken = claimToken,
            action = "complete",
            sql = """
                UPDATE workflow_delay_wakeups
                SET status = 'COMPLETED'
                WHERE run_id = ?
                    AND step_id = ?
                    AND claim_token = ?
                    AND status = 'CLAIMED'
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, runId)
            statement.setString(2, stepId)
            statement.setString(3, claimToken)
        }
    }

    /**
     * Extension API for startup recovery.
     *
     * This is intentionally public for JDBC-backed schedulers that need to
     * materialize missed ticks after process downtime, but it is not part of
     * the standard [WorkflowSchedulerStore] contract.
     */
    suspend fun recover(
        now: Instant = Instant.now(),
        limit: Int = 500,
    ): Int {
        require(limit > 0) { "JdbcWorkflowSchedulerStore.recover limit must be greater than zero" }
        return transaction { connection ->
            connection.prepareStatement(
                """
                SELECT schedule_id, workflow_name, cron_expression, timezone, next_fire_at, version, skip_calendar, business_hours_only
                FROM workflow_schedules
                WHERE enabled = TRUE
                    AND next_fire_at <= ?
                ORDER BY next_fire_at, schedule_id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """.trimIndent(),
            ).use { statement ->
                statement.setTimestamp(1, timestamp(now))
                statement.setInt(2, limit)
                statement.executeQuery().use { resultSet ->
                    var recovered = 0
                    while (resultSet.next()) {
                        val scheduleId = resultSet.getString("schedule_id")
                        val workflowName = resultSet.getString("workflow_name")
                        val schedule = CronSchedule.parse(
                            expression = resultSet.getString("cron_expression"),
                            zoneId = ZoneId.of(resultSet.getString("timezone")),
                            skipCalendar = decodeCalendarRules(resultSet.getString("skip_calendar")),
                            businessHoursOnly = resultSet.getBoolean("business_hours_only"),
                        )
                        val scheduledFireAt = resultSet.instant("next_fire_at")
                        val inserted = insertTickIfAbsent(
                            connection = connection,
                            tickId = tickId(scheduleId, scheduledFireAt),
                            scheduleId = scheduleId,
                            workflowName = workflowName,
                            scheduledFireAt = scheduledFireAt,
                        )
                        advanceSchedule(
                            connection = connection,
                            scheduleId = scheduleId,
                            nextFireAt = observedNextFireAfter(
                                schedule = schedule,
                                scheduleId = scheduleId,
                                workflowName = workflowName,
                                after = scheduledFireAt,
                            ),
                        )
                        if (inserted) {
                            recovered += 1
                            observer.onMissedTick(
                                workflowName = workflowName,
                                scheduledFireAt = scheduledFireAt,
                                reason = "scheduler_startup_recovery",
                                context = scheduledTickContext(
                                    tickId = tickId(scheduleId, scheduledFireAt),
                                    scheduleId = scheduleId,
                                    scheduledFireAt = scheduledFireAt,
                                ),
                            )
                        }
                    }
                    recovered
                }
            }
        }
    }

    fun createTableSql(): List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS workflow_schedules (
            schedule_id VARCHAR(255) PRIMARY KEY,
            workflow_name VARCHAR(255) NOT NULL,
            cron_expression VARCHAR(255) NOT NULL,
            timezone VARCHAR(255) DEFAULT 'UTC',
            next_fire_at TIMESTAMP NOT NULL,
            enabled BOOLEAN DEFAULT TRUE,
            skip_calendar TEXT,
            business_hours_only BOOLEAN DEFAULT FALSE,
            version BIGINT DEFAULT 0
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS workflow_schedule_ticks (
            tick_id VARCHAR(64) PRIMARY KEY,
            schedule_id VARCHAR(255) NOT NULL,
            workflow_name VARCHAR(255) NOT NULL,
            scheduled_fire_at TIMESTAMP NOT NULL,
            occurrence_index BIGINT NOT NULL DEFAULT 0,
            owner_id VARCHAR(255),
            claim_token VARCHAR(255),
            claim_expires_at TIMESTAMP,
            status VARCHAR(32) NOT NULL,
            workflow_run_id VARCHAR(255),
            terminal_reason VARCHAR(1024),
            CONSTRAINT fk_workflow_schedule_ticks_schedule
                FOREIGN KEY (schedule_id) REFERENCES workflow_schedules(schedule_id),
            CONSTRAINT uq_workflow_schedule_tick_occurrence
                UNIQUE(schedule_id, scheduled_fire_at, occurrence_index)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS workflow_delay_wakeups (
            run_id VARCHAR(255) NOT NULL,
            step_id VARCHAR(255) NOT NULL,
            resume_at TIMESTAMP NOT NULL,
            status VARCHAR(32) NOT NULL,
            owner_id VARCHAR(255),
            claim_token VARCHAR(255),
            claim_expires_at TIMESTAMP,
            PRIMARY KEY(run_id, step_id)
        )
        """.trimIndent(),
    )

    private fun reclaimExpiredTicks(
        connection: Connection,
        now: Instant,
        ownerId: String,
        claimExpiresAt: Instant,
        limit: Int,
    ): List<ClaimedScheduledTick> =
        connection.prepareStatement(
            """
            SELECT tick_id, schedule_id, workflow_name, scheduled_fire_at
            FROM workflow_schedule_ticks
            WHERE status IN ('CLAIMED', 'STARTED')
                AND claim_expires_at <= ?
            ORDER BY scheduled_fire_at, tick_id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, timestamp(now))
            statement.setInt(2, limit)
            statement.executeQuery().use { resultSet ->
                val ticks = mutableListOf<ClaimedScheduledTick>()
                while (resultSet.next()) {
                    val tickId = resultSet.getString("tick_id")
                    val claimToken = UUID.randomUUID().toString()
                    updateTickClaim(
                        connection = connection,
                        tickId = tickId,
                        ownerId = ownerId,
                        claimToken = claimToken,
                        claimExpiresAt = claimExpiresAt,
                    )
                    ticks += ClaimedScheduledTick(
                        tickId = tickId,
                        scheduleId = resultSet.getString("schedule_id"),
                        workflowName = resultSet.getString("workflow_name"),
                        scheduledFireAt = resultSet.instant("scheduled_fire_at"),
                        claimToken = claimToken,
                        claimExpiresAt = claimExpiresAt,
                    )
                }
                ticks
            }
        }

    private fun upsertScheduleSql(connection: Connection): String =
        if (connection.metaData.databaseProductName.equals("H2", ignoreCase = true)) {
            """
            MERGE INTO workflow_schedules target
            USING (
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ) source (
                schedule_id,
                workflow_name,
                cron_expression,
                timezone,
                next_fire_at,
                enabled,
                skip_calendar,
                business_hours_only
            )
            ON target.schedule_id = source.schedule_id
            WHEN MATCHED THEN UPDATE SET
                workflow_name = source.workflow_name,
                cron_expression = source.cron_expression,
                timezone = source.timezone,
                next_fire_at = source.next_fire_at,
                enabled = source.enabled,
                skip_calendar = source.skip_calendar,
                business_hours_only = source.business_hours_only,
                version = target.version + 1
            WHEN NOT MATCHED THEN INSERT (
                schedule_id,
                workflow_name,
                cron_expression,
                timezone,
                next_fire_at,
                enabled,
                skip_calendar,
                business_hours_only,
                version
            ) VALUES (
                source.schedule_id,
                source.workflow_name,
                source.cron_expression,
                source.timezone,
                source.next_fire_at,
                source.enabled,
                source.skip_calendar,
                source.business_hours_only,
                0
            )
            """.trimIndent()
        } else {
            """
            INSERT INTO workflow_schedules (
                schedule_id,
                workflow_name,
                cron_expression,
                timezone,
                next_fire_at,
                enabled,
                skip_calendar,
                business_hours_only,
                version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT (schedule_id) DO UPDATE SET
                workflow_name = EXCLUDED.workflow_name,
                cron_expression = EXCLUDED.cron_expression,
                timezone = EXCLUDED.timezone,
                next_fire_at = EXCLUDED.next_fire_at,
                enabled = EXCLUDED.enabled,
                skip_calendar = EXCLUDED.skip_calendar,
                business_hours_only = EXCLUDED.business_hours_only,
                version = workflow_schedules.version + 1
            """.trimIndent()
        }

    private fun createAndClaimDueTicks(
        connection: Connection,
        now: Instant,
        ownerId: String,
        claimExpiresAt: Instant,
        limit: Int,
    ): List<ClaimedScheduledTick> =
        connection.prepareStatement(
            """
            SELECT schedule_id, workflow_name, cron_expression, timezone, next_fire_at, skip_calendar, business_hours_only
            FROM workflow_schedules
            WHERE enabled = TRUE
                AND next_fire_at <= ?
            ORDER BY next_fire_at, schedule_id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, timestamp(now))
            statement.setInt(2, limit)
            statement.executeQuery().use { resultSet ->
                val claimed = mutableListOf<ClaimedScheduledTick>()
                while (resultSet.next()) {
                    val scheduleId = resultSet.getString("schedule_id")
                    val workflowName = resultSet.getString("workflow_name")
                    val schedule = CronSchedule.parse(
                        expression = resultSet.getString("cron_expression"),
                        zoneId = ZoneId.of(resultSet.getString("timezone")),
                        skipCalendar = decodeCalendarRules(resultSet.getString("skip_calendar")),
                        businessHoursOnly = resultSet.getBoolean("business_hours_only"),
                    )
                    val scheduledFireAt = resultSet.instant("next_fire_at")
                    val tickId = tickId(scheduleId, scheduledFireAt)
                    val inserted = insertTickIfAbsent(
                        connection = connection,
                        tickId = tickId,
                        scheduleId = scheduleId,
                        workflowName = workflowName,
                        scheduledFireAt = scheduledFireAt,
                    )
                    advanceSchedule(
                        connection = connection,
                        scheduleId = scheduleId,
                        nextFireAt = observedNextFireAfter(
                            schedule = schedule,
                            scheduleId = scheduleId,
                            workflowName = workflowName,
                            after = scheduledFireAt,
                        ),
                    )
                    if (inserted) {
                        val claimToken = UUID.randomUUID().toString()
                        updateTickClaim(
                            connection = connection,
                            tickId = tickId,
                            ownerId = ownerId,
                            claimToken = claimToken,
                            claimExpiresAt = claimExpiresAt,
                        )
                        claimed += ClaimedScheduledTick(
                            tickId = tickId,
                            scheduleId = scheduleId,
                            workflowName = workflowName,
                            scheduledFireAt = scheduledFireAt,
                            claimToken = claimToken,
                            claimExpiresAt = claimExpiresAt,
                        )
                    }
                }
                claimed
            }
        }

    private fun insertTickIfAbsent(
        connection: Connection,
        tickId: String,
        scheduleId: String,
        workflowName: String,
        scheduledFireAt: Instant,
    ): Boolean =
        connection.prepareStatement(
            """
            INSERT INTO workflow_schedule_ticks (
                tick_id,
                schedule_id,
                workflow_name,
                scheduled_fire_at,
                occurrence_index,
                owner_id,
                claim_token,
                claim_expires_at,
                status,
                workflow_run_id,
                terminal_reason
            ) VALUES (?, ?, ?, ?, 0, NULL, NULL, ?, 'CLAIMED', NULL, NULL)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tickId)
            statement.setString(2, scheduleId)
            statement.setString(3, workflowName)
            statement.setTimestamp(4, timestamp(scheduledFireAt))
            statement.setTimestamp(5, timestamp(Instant.EPOCH))
            try {
                statement.executeUpdate() > 0
            } catch (error: SQLException) {
                if (error.isUniqueConstraintViolation()) {
                    false
                } else {
                    throw error
                }
            }
        }

    private fun updateTickClaim(
        connection: Connection,
        tickId: String,
        ownerId: String,
        claimToken: String,
        claimExpiresAt: Instant,
    ) {
        connection.prepareStatement(
            """
            UPDATE workflow_schedule_ticks
            SET status = 'CLAIMED',
                owner_id = ?,
                claim_token = ?,
                claim_expires_at = ?,
                workflow_run_id = NULL
            WHERE tick_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ownerId)
            statement.setString(2, claimToken)
            statement.setTimestamp(3, timestamp(claimExpiresAt))
            statement.setString(4, tickId)
            statement.executeUpdate()
        }
    }

    private fun updateDelayWakeupClaim(
        connection: Connection,
        runId: String,
        stepId: String,
        ownerId: String,
        claimToken: String,
        claimExpiresAt: Instant,
    ) {
        connection.prepareStatement(
            """
            UPDATE workflow_delay_wakeups
            SET status = 'CLAIMED',
                owner_id = ?,
                claim_token = ?,
                claim_expires_at = ?
            WHERE run_id = ?
                AND step_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ownerId)
            statement.setString(2, claimToken)
            statement.setTimestamp(3, timestamp(claimExpiresAt))
            statement.setString(4, runId)
            statement.setString(5, stepId)
            statement.executeUpdate()
        }
    }

    private fun advanceSchedule(
        connection: Connection,
        scheduleId: String,
        nextFireAt: Instant,
    ) {
        connection.prepareStatement(
            """
            UPDATE workflow_schedules
            SET next_fire_at = ?,
                version = version + 1
            WHERE schedule_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, timestamp(nextFireAt))
            statement.setString(2, scheduleId)
            statement.executeUpdate()
        }
    }

    private fun observedNextFireAfter(
        schedule: CronSchedule,
        scheduleId: String,
        workflowName: String,
        after: Instant,
    ): Instant = schedule.nextFireAfter(after) { skippedFireAt, reason ->
        observer.onSkippedTick(
            workflowName = workflowName,
            scheduledFireAt = skippedFireAt,
            reason = reason,
            context = scheduledTickContext(
                tickId = tickId(scheduleId, skippedFireAt),
                scheduleId = scheduleId,
                scheduledFireAt = skippedFireAt,
            ),
        )
    }

    private fun markTerminalTick(
        tickId: String,
        claimToken: String,
        status: String,
        reason: String?,
    ) {
        updateClaimedTick(
            tickId = tickId,
            claimToken = claimToken,
            terminalAction = status.lowercase(),
            sql = """
                UPDATE workflow_schedule_ticks
                SET status = ?,
                    terminal_reason = ?
                WHERE tick_id = ?
                    AND claim_token = ?
                    AND status IN ('CLAIMED', 'STARTED')
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, status)
            statement.setString(2, reason)
            statement.setString(3, tickId)
            statement.setString(4, claimToken)
        }
    }

    private fun updateClaimedTick(
        tickId: String,
        claimToken: String,
        terminalAction: String,
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                val updated = statement.executeUpdate()
                if (updated == 0) {
                    throw IllegalArgumentException("Cannot $terminalAction scheduled tick '$tickId'; claim token does not match")
                }
            }
        }
    }

    private fun updateClaimedDelayWakeup(
        runId: String,
        stepId: String,
        claimToken: String,
        action: String,
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                val updated = statement.executeUpdate()
                if (updated == 0) {
                    throw IllegalArgumentException(
                        "Cannot $action delay wakeup '${delayWakeupId(runId, stepId)}'; claim token does not match",
                    )
                }
            }
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T {
        val connection = dataSource.connection
        try {
            val originalAutoCommit = connection.autoCommit
            connection.autoCommit = false
            return try {
                val result = block(connection)
                connection.commit()
                result
            } catch (error: Throwable) {
                try {
                    connection.rollback()
                } catch (rollbackError: Throwable) {
                    error.addSuppressed(rollbackError)
                }
                throw error
            } finally {
                connection.autoCommit = originalAutoCommit
            }
        } finally {
            connection.close()
        }
    }

    private fun ResultSet.toScheduleRecord(): ScheduleRecord {
        val expression = getString("cron_expression")
        val zoneId = ZoneId.of(getString("timezone") ?: "UTC")
        val skipCalendar = decodeCalendarRules(getString("skip_calendar"))
        val businessHoursOnly = getBoolean("business_hours_only")
        return ScheduleRecord(
            scheduleId = getString("schedule_id"),
            workflowName = getString("workflow_name"),
            schedule = CronSchedule.parse(
                expression = expression,
                zoneId = zoneId,
                skipCalendar = skipCalendar,
                businessHoursOnly = businessHoursOnly,
            ),
            nextFireAt = instant("next_fire_at"),
            enabled = getBoolean("enabled"),
            skipCalendar = skipCalendar,
            businessHoursOnly = businessHoursOnly,
        )
    }

    private fun scheduledTickContext(
        tickId: String,
        scheduleId: String,
        scheduledFireAt: Instant,
    ): WorkflowContext = WorkflowContext(
        attributes = mapOf(
            "tramai.schedule.tick_id" to tickId,
            "tramai.schedule.schedule_id" to scheduleId,
            "tramai.schedule.scheduled_fire_at_epoch_millis" to scheduledFireAt.toEpochMilli(),
        ),
    )
}

private fun cronSchedule(
    schedule: WorkflowScheduleDefinition,
    owner: String,
): CronSchedule = schedule as? CronSchedule
    ?: throw IllegalArgumentException("$owner only supports CronSchedule records; got kind='${schedule.kind}'")

private fun tickId(
    scheduleId: String,
    scheduledFireAt: Instant,
    occurrenceIndex: Long = 0,
): String = MessageDigest
    .getInstance("SHA-256")
    .digest("$scheduleId:${scheduledFireAt.toEpochMilli()}:$occurrenceIndex".toByteArray())
    .joinToString(separator = "") { byte ->
        byte.toInt().and(0xff).toString(16).padStart(2, '0')
    }

private fun delayWakeupId(
    runId: String,
    stepId: String,
): String = "$runId:$stepId"

private fun timestamp(instant: Instant): Timestamp = Timestamp.from(instant)

private fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

private fun SQLException.isUniqueConstraintViolation(): Boolean =
    sqlState == "23505"

private fun encodeCalendarRules(rules: List<CalendarRule>): String =
    rules.joinToString(prefix = "[", postfix = "]") { rule ->
        when (rule) {
            is CalendarRule.FixedDate ->
                """{"type":"fixed_date","month":${rule.month},"dayOfMonth":${rule.dayOfMonth}}"""
            is CalendarRule.NthWeekdayOfMonth ->
                """{"type":"nth_weekday_of_month","month":${rule.month},"nth":${rule.nth},"dayOfWeek":${rule.dayOfWeek.value}}"""
            is CalendarRule.DateRange ->
                """{"type":"date_range","startMonth":${rule.startMonth},"startDayOfMonth":${rule.startDayOfMonth},"endMonth":${rule.endMonth},"endDayOfMonth":${rule.endDayOfMonth}}"""
        }
    }

private fun decodeCalendarRules(payload: String?): List<CalendarRule> {
    if (payload.isNullOrBlank() || payload.trim() == "[]") {
        return emptyList()
    }
    val trimmed = payload.trim()
    require(trimmed.startsWith("[") && trimmed.endsWith("]")) {
        "Calendar rules payload must be a JSON array"
    }
    val body = trimmed.substring(1, trimmed.length - 1).trim()
    if (body.isEmpty()) {
        return emptyList()
    }
    return splitJsonObjects(body).map { objectPayload ->
        val fields = parseCalendarRuleObject(objectPayload)
        when (fields["type"]) {
            "fixed_date" -> CalendarRule.FixedDate(
                month = fields.requiredInt("month"),
                dayOfMonth = fields.requiredInt("dayOfMonth"),
            )
            "nth_weekday_of_month" -> CalendarRule.NthWeekdayOfMonth(
                month = fields.requiredInt("month"),
                nth = fields.requiredInt("nth"),
                dayOfWeek = DayOfWeek.of(fields.requiredInt("dayOfWeek")),
            )
            "date_range" -> CalendarRule.DateRange(
                startMonth = fields.requiredInt("startMonth"),
                startDayOfMonth = fields.requiredInt("startDayOfMonth"),
                endMonth = fields.requiredInt("endMonth"),
                endDayOfMonth = fields.requiredInt("endDayOfMonth"),
            )
            else -> throw IllegalArgumentException("Unknown calendar rule type '${fields["type"]}'")
        }.also { it.validate() }
    }
}

private fun Map<String, String>.requiredInt(name: String): Int =
    this[name]?.toIntOrNull()
        ?: throw IllegalArgumentException("Calendar rule field '$name' must be an integer")

private fun splitJsonObjects(body: String): List<String> {
    val objects = mutableListOf<String>()
    var index = 0
    while (index < body.length) {
        while (index < body.length && body[index].isWhitespace()) {
            index++
        }
        require(index < body.length && body[index] == '{') {
            "Calendar rules payload must contain JSON objects"
        }
        val start = index
        var foundEnd = false
        var inString = false
        var escaped = false
        var depth = 0
        while (index < body.length) {
            val char = body[index]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> depth++
                !inString && char == '}' -> {
                    depth--
                    if (depth == 0) {
                        index++
                        objects += body.substring(start, index)
                        foundEnd = true
                        break
                    }
                }
            }
            index++
        }
        require(foundEnd) {
            "Calendar rules payload contains an unterminated object"
        }
        while (index < body.length && body[index].isWhitespace()) {
            index++
        }
        if (index < body.length) {
            require(body[index] == ',') {
                "Calendar rules payload must separate objects with commas"
            }
            index++
            require(index < body.length) {
                "Calendar rules payload must not end with a trailing comma"
            }
        }
    }
    return objects
}

private fun parseCalendarRuleObject(payload: String): Map<String, String> {
    val trimmed = payload.trim()
    require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
        "Calendar rule must be a JSON object"
    }
    val body = trimmed.substring(1, trimmed.length - 1).trim()
    if (body.isEmpty()) {
        return emptyMap()
    }
    val fields = linkedMapOf<String, String>()
    var index = 0
    while (index < body.length) {
        while (index < body.length && body[index].isWhitespace()) {
            index++
        }
        val key = readJsonString(body, index, "Calendar rule field name")
        index = key.nextIndex
        while (index < body.length && body[index].isWhitespace()) {
            index++
        }
        require(index < body.length && body[index] == ':') {
            "Calendar rule field '${key.value}' must be followed by ':'"
        }
        index++
        while (index < body.length && body[index].isWhitespace()) {
            index++
        }
        val value = if (index < body.length && body[index] == '"') {
            val stringValue = readJsonString(body, index, "Calendar rule field '${key.value}'")
            index = stringValue.nextIndex
            stringValue.value
        } else {
            val start = index
            if (index < body.length && body[index] == '-') {
                index++
            }
            while (index < body.length && body[index].isDigit()) {
                index++
            }
            require(index > start && body.substring(start, index) != "-") {
                "Calendar rule field '${key.value}' must be a string or integer"
            }
            body.substring(start, index)
        }
        require(fields.put(key.value, value) == null) {
            "Calendar rule field '${key.value}' is duplicated"
        }
        while (index < body.length && body[index].isWhitespace()) {
            index++
        }
        if (index < body.length) {
            require(body[index] == ',') {
                "Calendar rule fields must be separated by commas"
            }
            index++
            require(index < body.length) {
                "Calendar rule must not end with a trailing comma"
            }
        }
    }
    return fields
}

private data class JsonStringToken(
    val value: String,
    val nextIndex: Int,
)

private fun readJsonString(
    text: String,
    startIndex: Int,
    owner: String,
): JsonStringToken {
    require(startIndex < text.length && text[startIndex] == '"') {
        "$owner must be a JSON string"
    }
    val value = StringBuilder()
    var index = startIndex + 1
    while (index < text.length) {
        val char = text[index]
        if (char == '"') {
            return JsonStringToken(value.toString(), index + 1)
        }
        if (char == '\\') {
            require(index + 1 < text.length) { "$owner contains an unterminated escape sequence" }
            val escaped = text[index + 1]
            value.append(
                when (escaped) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'
                    'f' -> '\u000C'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> throw IllegalArgumentException("$owner contains unsupported escape '\\$escaped'")
                },
            )
            index += 2
        } else {
            value.append(char)
            index++
        }
    }
    throw IllegalArgumentException("$owner is unterminated")
}
