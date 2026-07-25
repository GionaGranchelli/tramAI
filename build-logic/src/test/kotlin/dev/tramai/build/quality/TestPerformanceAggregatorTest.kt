package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestPerformanceAggregatorTest {
    @Test
    fun `aggregates three measured runs using median and retains observations`() {
        val result = TestPerformanceAggregator().aggregate(
            listOf(observation(1, 300), observation(2, 100), observation(3, 200))
        )
        assertEquals(200, result.byModule.getValue(":core").medianDurationMs)
        assertEquals(3, result.observations.size)
        assertEquals(20, result.slowestTests.single().durationMs)
    }

    @Test
    fun `requires exactly three runs`() {
        assertFailsWith<GradleException> {
            TestPerformanceAggregator().aggregate(listOf(observation(1, 100), observation(2, 200)))
        }
    }

    private fun observation(run: Int, duration: Long) = TestPerformanceObservation(
        run = run,
        module = ":core",
        durationMs = duration,
        testCount = 1,
        skippedCount = 0,
        failureCount = 0,
        jdkVersion = "21",
        gradleVersion = "9.0",
        testTimings = listOf(TestTiming(":core", "CoreTest", "works", duration / 10))
    )
}
