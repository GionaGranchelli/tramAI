package dev.tramai.scheduler

import dev.tramai.orchestration.WorkflowScheduleDefinition
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Creates a cron schedule for a workflow.
 *
 * Five-field expressions are interpreted as minute, hour, day-of-month, month,
 * day-of-week with seconds fixed to zero. Six-field expressions add seconds as
 * the first field and are intended for tests and high-frequency local timers.
 */
fun at(
    expression: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
    skipCalendar: List<CalendarRule> = emptyList(),
    businessHoursOnly: Boolean = false,
): CronSchedule = CronSchedule.parse(
    expression = expression,
    zoneId = zoneId,
    skipCalendar = skipCalendar,
    businessHoursOnly = businessHoursOnly,
)

fun at(
    expression: String,
    zone: String,
    skipCalendar: List<CalendarRule> = emptyList(),
    businessHoursOnly: Boolean = false,
): CronSchedule = CronSchedule.parse(
    expression = expression,
    zoneId = ZoneId.of(zone),
    skipCalendar = skipCalendar,
    businessHoursOnly = businessHoursOnly,
)

fun dailyAt(
    hour: Int,
    minute: Int,
    second: Int = 0,
    zoneId: ZoneId = ZoneId.systemDefault(),
    skipCalendar: List<CalendarRule> = emptyList(),
    businessHoursOnly: Boolean = false,
): CronSchedule {
    require(hour in 0..23) { "dailyAt hour must be in range 0..23" }
    require(minute in 0..59) { "dailyAt minute must be in range 0..59" }
    require(second in 0..59) { "dailyAt second must be in range 0..59" }
    return at(
        expression = "$second $minute $hour * * *",
        zoneId = zoneId,
        skipCalendar = skipCalendar,
        businessHoursOnly = businessHoursOnly,
    )
}

fun dailyAt(
    hour: Int,
    minute: Int,
    second: Int = 0,
    zone: String,
    skipCalendar: List<CalendarRule> = emptyList(),
    businessHoursOnly: Boolean = false,
): CronSchedule = dailyAt(
    hour = hour,
    minute = minute,
    second = second,
    zoneId = ZoneId.of(zone),
    skipCalendar = skipCalendar,
    businessHoursOnly = businessHoursOnly,
)

class CronSchedule internal constructor(
    override val expression: String,
    override val zoneId: ZoneId,
    val skipCalendar: List<CalendarRule> = emptyList(),
    val businessHoursOnly: Boolean = false,
    private val seconds: CronField,
    private val minutes: CronField,
    private val hours: CronField,
    private val daysOfMonth: CronField,
    private val months: CronField,
    private val daysOfWeek: CronField,
    private val hasExplicitSeconds: Boolean,
) : WorkflowScheduleDefinition {
    override val kind: String = "cron"

    override fun validate() {
        skipCalendar.forEach { it.validate() }
        nextFireAfter(Instant.now())
    }

    override fun canonicalForm(): String =
        "$kind:${zoneId.id}:$expression:businessHoursOnly=$businessHoursOnly:skipCalendar=${skipCalendar.joinToString("|") { it.description }}"

    fun nextFireAfter(
        after: Instant,
        onSkippedTick: (scheduledFireAt: Instant, reason: String) -> Unit = { _, _ -> },
    ): Instant {
        var searchAfter = after
        val deadline = ZonedDateTime.ofInstant(after, zoneId).plusYears(5).toInstant()
        while (!searchAfter.isAfter(deadline)) {
            val fireAt = nextCronFireAfter(searchAfter)
            if (fireAt.isAfter(deadline)) {
                break
            }
            val calendarRule = matchingCalendarRule(fireAt)
            if (calendarRule != null) {
                onSkippedTick(fireAt, "calendar_skip:${calendarRule.description}")
                searchAfter = fireAt
                continue
            }
            if (businessHoursOnly && !isBusinessHour(fireAt)) {
                onSkippedTick(fireAt, "business_hours")
                val adjusted = nextBusinessHourStart(fireAt)
                val adjustedRule = matchingCalendarRule(adjusted)
                if (adjustedRule != null) {
                    onSkippedTick(adjusted, "calendar_skip:${adjustedRule.description}")
                    searchAfter = adjusted
                    continue
                }
                return adjusted
            }
            return fireAt
        }
        throw IllegalArgumentException(
            "Cron expression '$expression' has no fire time within five years in timezone '${zoneId.id}'",
        )
    }

    private fun nextCronFireAfter(after: Instant): Instant {
        var candidate = ZonedDateTime.ofInstant(after, zoneId)
            .plusSeconds(1)
            .truncatedTo(ChronoUnit.SECONDS)
        if (!hasExplicitSeconds) {
            candidate = candidate.truncatedTo(ChronoUnit.MINUTES)
            if (!candidate.toInstant().isAfter(after)) {
                candidate = candidate.plusMinutes(1)
            }
        }
        val deadline = candidate.plusYears(5)
        while (!candidate.isAfter(deadline)) {
            candidate = when {
                !months.matches(candidate.monthValue) -> candidate.plusDays(1).truncatedTo(ChronoUnit.DAYS)
                !matchesDay(candidate) -> candidate.plusDays(1).truncatedTo(ChronoUnit.DAYS)
                !hours.matches(candidate.hour) -> candidate.plusHours(1).truncatedTo(ChronoUnit.HOURS)
                !minutes.matches(candidate.minute) -> candidate.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES)
                !seconds.matches(candidate.second) -> candidate.plusSeconds(1).truncatedTo(ChronoUnit.SECONDS)
                else -> return candidate.toInstant()
            }
            if (matches(candidate)) {
                return candidate.toInstant()
            }
        }
        throw IllegalArgumentException(
            "Cron expression '$expression' has no fire time within five years in timezone '${zoneId.id}'",
        )
    }

    internal fun matches(time: ZonedDateTime): Boolean =
        seconds.matches(time.second) &&
            minutes.matches(time.minute) &&
            hours.matches(time.hour) &&
            months.matches(time.monthValue) &&
            matchesDay(time)

    private fun matchesDay(time: ZonedDateTime): Boolean {
        val dayOfMonthMatches = daysOfMonth.matches(time.dayOfMonth)
        val dayOfWeekMatches = daysOfWeek.matches(time.dayOfWeek.value % 7)
        return when {
            daysOfMonth.isWildcard && daysOfWeek.isWildcard -> true
            daysOfMonth.isWildcard -> dayOfWeekMatches
            daysOfWeek.isWildcard -> dayOfMonthMatches
            else -> dayOfMonthMatches || dayOfWeekMatches
        }
    }

    private fun matchingCalendarRule(fireAt: Instant): CalendarRule? {
        if (skipCalendar.isEmpty()) {
            return null
        }
        val date = ZonedDateTime.ofInstant(fireAt, zoneId).toLocalDate()
        return skipCalendar.firstOrNull { it.matches(date) }
    }

    private fun isBusinessHour(fireAt: Instant): Boolean {
        val time = ZonedDateTime.ofInstant(fireAt, zoneId)
        return time.dayOfWeek.value in 1..5 &&
            !time.toLocalTime().isBefore(BUSINESS_START) &&
            time.toLocalTime().isBefore(BUSINESS_END)
    }

    private fun nextBusinessHourStart(fireAt: Instant): Instant {
        var time = ZonedDateTime.ofInstant(fireAt, zoneId)
        if (time.dayOfWeek.value in 1..5 && time.toLocalTime().isBefore(BUSINESS_START)) {
            return time.with(BUSINESS_START).truncatedTo(ChronoUnit.SECONDS).toInstant()
        }
        time = time.plusDays(1).truncatedTo(ChronoUnit.DAYS).with(BUSINESS_START)
        while (time.dayOfWeek.value !in 1..5) {
            time = time.plusDays(1)
        }
        return time.toInstant()
    }

    companion object {
        private val BUSINESS_START: LocalTime = LocalTime.of(9, 0)
        private val BUSINESS_END: LocalTime = LocalTime.of(18, 0)

        fun parse(
            expression: String,
            zoneId: ZoneId = ZoneId.systemDefault(),
            skipCalendar: List<CalendarRule> = emptyList(),
            businessHoursOnly: Boolean = false,
        ): CronSchedule {
            val trimmed = expression.trim()
            require(trimmed.isNotEmpty()) { "Cron expression must not be blank" }
            val parts = trimmed.split(Regex("\\s+"))
            require(parts.size == 5 || parts.size == 6) {
                "Cron expression '$expression' must have 5 fields or 6 fields with seconds"
            }
            val offset = if (parts.size == 6) 1 else 0
            val seconds = if (parts.size == 6) {
                CronField.parse(parts[0], 0, 59, "seconds")
            } else {
                CronField.exact(0, "seconds")
            }
            return CronSchedule(
                expression = trimmed,
                zoneId = zoneId,
                skipCalendar = skipCalendar,
                businessHoursOnly = businessHoursOnly,
                seconds = seconds,
                minutes = CronField.parse(parts[offset], 0, 59, "minutes"),
                hours = CronField.parse(parts[offset + 1], 0, 23, "hours"),
                daysOfMonth = CronField.parse(parts[offset + 2], 1, 31, "day-of-month"),
                months = CronField.parse(parts[offset + 3], 1, 12, "month"),
                daysOfWeek = CronField.parse(parts[offset + 4], 0, 7, "day-of-week", normalizeSunday = true),
                hasExplicitSeconds = parts.size == 6,
            ).also { it.validate() }
        }
    }
}

sealed interface CalendarRule {
    val description: String
    fun matches(date: LocalDate): Boolean
    fun validate()

    data class FixedDate(
        val month: Int,
        val dayOfMonth: Int,
    ) : CalendarRule {
        override val description: String = "fixed_date:$month-$dayOfMonth"

        override fun matches(date: LocalDate): Boolean =
            date.monthValue == month && date.dayOfMonth == dayOfMonth

        override fun validate() {
            requireValidMonthDay(month, dayOfMonth, "FixedDate")
        }
    }

    data class NthWeekdayOfMonth(
        val month: Int,
        val nth: Int,
        val dayOfWeek: DayOfWeek,
    ) : CalendarRule {
        override val description: String = "nth_weekday:$nth:${dayOfWeek.value}:$month"

        override fun matches(date: LocalDate): Boolean {
            if (date.monthValue != month || date.dayOfWeek != dayOfWeek) {
                return false
            }
            val target = date.withDayOfMonth(1).with(TemporalAdjusters.dayOfWeekInMonth(nth, dayOfWeek))
            return date == target
        }

        override fun validate() {
            require(month in 1..12) { "NthWeekdayOfMonth month must be in range 1..12" }
            require(nth in 1..5) { "NthWeekdayOfMonth nth must be in range 1..5" }
        }
    }

    data class DateRange(
        val startMonth: Int,
        val startDayOfMonth: Int,
        val endMonth: Int,
        val endDayOfMonth: Int,
    ) : CalendarRule {
        override val description: String =
            "date_range:$startMonth-$startDayOfMonth:$endMonth-$endDayOfMonth"

        override fun matches(date: LocalDate): Boolean {
            val current = MonthDay.of(date.monthValue, date.dayOfMonth)
            val start = MonthDay.of(startMonth, startDayOfMonth)
            val end = MonthDay.of(endMonth, endDayOfMonth)
            return if (start <= end) {
                current >= start && current <= end
            } else {
                current >= start || current <= end
            }
        }

        override fun validate() {
            requireValidMonthDay(startMonth, startDayOfMonth, "DateRange start")
            requireValidMonthDay(endMonth, endDayOfMonth, "DateRange end")
        }
    }
}

private fun requireValidMonthDay(
    month: Int,
    dayOfMonth: Int,
    owner: String,
) {
    require(month in 1..12) { "$owner month must be in range 1..12" }
    require(dayOfMonth >= 1) { "$owner dayOfMonth must be greater than zero" }
    try {
        LocalDate.of(2021, month, dayOfMonth)
    } catch (error: RuntimeException) {
        throw IllegalArgumentException("$owner date $month-$dayOfMonth is not valid every year", error)
    }
}

internal data class CronField(
    val values: Set<Int>,
    val isWildcard: Boolean,
) {
    fun matches(value: Int): Boolean = values.contains(value)

    companion object {
        fun exact(value: Int, name: String): CronField = CronField(
            values = setOf(value),
            isWildcard = false,
        )

        fun parse(
            text: String,
            min: Int,
            max: Int,
            name: String,
            normalizeSunday: Boolean = false,
        ): CronField {
            require(text.isNotBlank()) { "Cron $name field must not be blank" }
            val values = linkedSetOf<Int>()
            var wildcard = false
            for (segment in text.split(",")) {
                require(segment.isNotBlank()) {
                    "Cron $name field '$text' contains an empty list segment"
                }
                val rangeAndStep = segment.split("/")
                require(rangeAndStep.size <= 2) {
                    "Cron $name field segment '$segment' has too many step separators"
                }
                val step = rangeAndStep.getOrNull(1)?.toIntOrNull()
                    ?: 1
                require(step > 0) {
                    "Cron $name field segment '$segment' must use a positive step"
                }
                val rangeText = rangeAndStep[0]
                val range = parseRange(
                    rangeText = rangeText,
                    min = min,
                    max = max,
                    name = name,
                    normalizeSunday = normalizeSunday,
                )
                val steppedRange = if (rangeAndStep.size == 2 && rangeText != "*" && range.first == range.last) {
                    range.first..max
                } else {
                    range
                }
                wildcard = wildcard || rangeText == "*"
                var current = steppedRange.first
                while (current <= steppedRange.last) {
                    values += normalize(value = current, max = max, normalizeSunday = normalizeSunday)
                    current += step
                }
            }
            require(values.isNotEmpty()) {
                "Cron $name field '$text' does not select any value"
            }
            return CronField(
                values = values,
                isWildcard = wildcard && values.size == (max - min + 1 - if (normalizeSunday) 1 else 0),
            )
        }

        private fun parseRange(
            rangeText: String,
            min: Int,
            max: Int,
            name: String,
            normalizeSunday: Boolean,
        ): IntRange {
            if (rangeText == "*") {
                return min..max
            }
            val bounds = rangeText.split("-")
            return when (bounds.size) {
                1 -> {
                    val value = parseValue(bounds[0], min, max, name, normalizeSunday)
                    value..value
                }
                2 -> {
                    val start = parseValue(bounds[0], min, max, name, normalizeSunday)
                    val end = parseValue(bounds[1], min, max, name, normalizeSunday)
                    require(start <= end) {
                        "Cron $name field range '$rangeText' must not descend"
                    }
                    start..end
                }
                else -> throw IllegalArgumentException(
                    "Cron $name field range '$rangeText' has too many range separators",
                )
            }
        }

        private fun parseValue(
            text: String,
            min: Int,
            max: Int,
            name: String,
            normalizeSunday: Boolean,
        ): Int {
            val value = text.toIntOrNull()
                ?: throw IllegalArgumentException("Cron $name field value '$text' is not an integer")
            require(value in min..max) {
                "Cron $name field value '$value' is outside allowed range $min..$max"
            }
            return normalize(value = value, max = max, normalizeSunday = normalizeSunday)
        }

        private fun normalize(
            value: Int,
            max: Int,
            normalizeSunday: Boolean,
        ): Int = if (normalizeSunday && value == max) 0 else value
    }
}
