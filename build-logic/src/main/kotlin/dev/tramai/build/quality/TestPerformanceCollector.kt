package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class TestPerformanceCollector(
    private val repositoryRoot: File,
    private val configuration: TestQualityConfiguration
) {
    fun collectMeasuredRun(
        run: Int,
        gradleVersion: String,
        jdkVersion: String = Runtime.version().feature().toString(),
        reportRoot: File? = null
    ): List<TestPerformanceObservation> {
        require(run in 1..3) { "Measured test run must be 1, 2, or 3" }
        return configuration.criticalModules.sorted().map { module ->
            val moduleName = module.removePrefix(":").replace(":", "/")
            val resultDir = reportRoot?.let { File(it, "$run/$moduleName") }
                ?: File(repositoryRoot, "$moduleName/build/test-results/test")
            val reports = resultDir.listFiles { file ->
                file.isFile && file.name.startsWith("TEST-") && file.extension == "xml"
            }?.sortedBy { it.name }.orEmpty()
            if (reports.isEmpty()) {
                throw GradleException("Missing expected test report for $module run $run at ${resultDir.absolutePath}")
            }

            val classTimings = mutableListOf<TestTiming>()
            val testTimings = mutableListOf<TestTiming>()
            var tests = 0
            var skipped = 0
            var failures = 0
            var duration = 0L
            reports.forEach { report ->
                val suite = parse(report)
                val className = suite.getAttribute("name").ifBlank {
                    report.name.removePrefix("TEST-").removeSuffix(".xml")
                }
                val suiteDuration = millis(suite.getAttribute("time"))
                val suiteTests = suite.getAttribute("tests").toIntOrNull() ?: 0
                val suiteSkipped = suite.getAttribute("skipped").toIntOrNull() ?: 0
                val suiteFailures = (suite.getAttribute("failures").toIntOrNull() ?: 0) +
                    (suite.getAttribute("errors").toIntOrNull() ?: 0)
                duration += suiteDuration
                tests += suiteTests
                skipped += suiteSkipped
                failures += suiteFailures
                classTimings += TestTiming(
                    module = module,
                    className = className,
                    testName = "<class>",
                    durationMs = suiteDuration,
                    skipped = suiteSkipped == suiteTests && suiteTests > 0,
                    failed = suiteFailures > 0
                )

                val cases = suite.getElementsByTagName("testcase")
                for (index in 0 until cases.length) {
                    val case = cases.item(index) as? Element ?: continue
                    testTimings += TestTiming(
                        module = module,
                        className = case.getAttribute("classname").ifBlank { className },
                        testName = case.getAttribute("name").ifBlank { "<unknown>" },
                        durationMs = millis(case.getAttribute("time")),
                        skipped = case.getElementsByTagName("skipped").length > 0,
                        failed = case.getElementsByTagName("failure").length > 0 ||
                            case.getElementsByTagName("error").length > 0
                    )
                }
            }
            TestPerformanceObservation(
                run = run,
                module = module,
                durationMs = duration,
                testCount = tests,
                skippedCount = skipped,
                failureCount = failures,
                sourceSet = "test",
                testTaskName = "test",
                jdkVersion = jdkVersion,
                gradleVersion = gradleVersion,
                classTimings = classTimings.sortedWith(compareBy<TestTiming> { it.className }.thenBy { it.testName }),
                testTimings = testTimings.sortedWith(compareBy<TestTiming> { it.className }.thenBy { it.testName })
            )
        }
    }

    private fun parse(file: File): Element = try {
        secureFactory().newDocumentBuilder().parse(file).documentElement
    } catch (e: Exception) {
        throw GradleException("Malformed test XML ${file.absolutePath}: ${e.message}", e)
    }

    private fun millis(seconds: String): Long =
        seconds.toBigDecimalOrNull()?.multiply(1000.toBigDecimal())?.toLong() ?: 0L

    private fun secureFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
}
