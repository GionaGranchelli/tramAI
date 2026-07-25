package dev.tramai.build.quality

import org.gradle.api.GradleException
import java.security.MessageDigest

class TestPerformanceAggregator {
    fun aggregate(observations: List<TestPerformanceObservation>): TestPerformanceData {
        if (observations.isEmpty()) throw GradleException("Test-performance observations are empty")
        val byModuleObservations = observations.groupBy { it.module }.toSortedMap()
        byModuleObservations.forEach { (module, measured) ->
            val runs = measured.map { it.run }.sorted()
            if (runs != listOf(1, 2, 3)) {
                throw GradleException("$module must have exactly three measured runs; found $runs")
            }
            val environments = measured.map { it.jdkVersion to it.gradleVersion }.distinct()
            if (environments.size != 1) {
                throw GradleException("$module observations use inconsistent JDK/Gradle versions: $environments")
            }
        }

        val byModule = byModuleObservations.mapValues { (module, measured) ->
            ModuleTestPerformance(
                module = module,
                totalDurationMs = median(measured.map { it.durationMs }),
                medianDurationMs = median(measured.map { it.durationMs }),
                testCount = measured.maxOf { it.testCount },
                skippedCount = measured.maxOf { it.skippedCount },
                failureCount = measured.maxOf { it.failureCount },
                sourceSet = measured.first().sourceSet,
                testTaskName = measured.first().testTaskName
            )
        }
        val classTimings = aggregateTimings(observations.flatMap { it.classTimings })
        val testTimings = aggregateTimings(observations.flatMap { it.testTimings })
        // Build byIdentity from ALL test timings (not just slowestTests)
        val allTestTimings = aggregateTimings(observations.flatMap { it.testTimings })
        val byIdentity = allTestTimings.associateBy { testIdentity(it) }
        return TestPerformanceData(
            status = "measured",
            observations = observations.sortedWith(compareBy<TestPerformanceObservation> { it.run }.thenBy { it.module }),
            byModule = byModule,
            slowestClasses = classTimings.sortedByDescending { it.durationMs }.take(20),
            slowestTests = testTimings.sortedByDescending { it.durationMs }.take(20),
            allTests = allTestTimings,
            byIdentity = byIdentity,
            totalDurationMs = byModule.values.sumOf { it.medianDurationMs },
            totalTestCount = byModule.values.sumOf { it.testCount }
        )
    }

    private fun aggregateTimings(timings: List<TestTiming>): List<TestTiming> =
        timings.groupBy { listOf(it.module, it.className, it.testName, it.sourceSet, it.testTaskName) }
            .map { (_, values) ->
                values.first().copy(
                    durationMs = median(values.map { it.durationMs }),
                    skipped = values.any { it.skipped },
                    failed = values.any { it.failed }
                )
            }

    private fun testIdentity(timing: TestTiming): String {
        val canonical = listOf(timing.module, timing.className, timing.testName, timing.sourceSet, timing.testTaskName)
            .joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun median(values: List<Long>): Long {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
