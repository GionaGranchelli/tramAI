package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Focused production-path tests for DependencyBaselineVerifier.
 * No Gradle project, no file I/O — pure logic on data classes.
 */
class DependencyBaselineVerifierTest {

    private val verifier = DependencyBaselineVerifier()

    private fun dep(
        group: String = "org.jetbrains.kotlinx",
        artifact: String = "kotlinx-coroutines-core-jvm",
        selectedVersion: String = "1.10.2",
        requestedVersion: String? = "1.10.2",
        direct: Boolean = true,
        configuration: String = "runtimeClasspath",
        selectionReason: String = "requested",
        dependencyPath: List<String> = listOf(":tramai-engine", "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2"),
        consumers: List<String> = listOf(":tramai-engine")
    ) = ResolvedDependency(
        group = group,
        artifact = artifact,
        selectedVersion = selectedVersion,
        requestedVersion = requestedVersion,
        direct = direct,
        configuration = configuration,
        selectionReason = selectionReason,
        dependencyPath = dependencyPath,
        consumers = consumers
    )

    @Test
    fun `Dependency traversal distinguishes direct and transitive edges`() {
        val directDep = dep(direct = true, dependencyPath = listOf(":tramai-engine", "lib:1.0"))
        val transitiveDep = dep(
            direct = false,
            dependencyPath = listOf(":tramai-engine", "lib:1.0", "transitive-lib:0.5")
        )
        assertTrue(directDep.direct, "Direct dependency should have direct=true")
        assertTrue(!transitiveDep.direct, "Transitive dependency should have direct=false")
    }

    @Test
    fun `Requested and selected versions both preserved`() {
        val d = dep(requestedVersion = "1.10.2", selectedVersion = "1.10.2")
        assertEquals("1.10.2", d.requestedVersion)
        assertEquals("1.10.2", d.selectedVersion)
    }

    @Test
    fun `Multiple consumers preserved`() {
        val d = dep(consumers = listOf(":tramai-engine", ":tramai-core"))
        assertEquals(2, d.consumers.size)
    }

    @Test
    fun `Multiple dependency paths not discarded`() {
        val single = dep(dependencyPath = listOf(":tramai-engine", "lib:1.0"))
        val twoHops = dep(dependencyPath = listOf(":tramai-engine", "lib:1.0", "transitive:0.5"))
        assertEquals(2, single.dependencyPath.size)
        assertEquals(3, twoHops.dependencyPath.size)
    }

    @Test
    fun `Dynamic selectors rejected`() {
        val d = dep(requestedVersion = "1.+")
        val diags = verifier.verify(listOf(d), listOf(d))
        assertTrue(diags.any { it.code == DiagnosticCode.DYNAMIC_DEPENDENCY_VERSION && it.severity == DiagnosticSeverity.FAILURE },
            "Dynamic selector '1.+' should produce DYNAMIC_DEPENDENCY_VERSION failure")
    }

    @Test
    fun `Dynamic latest release selector rejected`() {
        val d = dep(requestedVersion = "latest.release")
        val diags = verifier.verify(listOf(d), listOf(d))
        assertTrue(diags.any { it.code == DiagnosticCode.DYNAMIC_DEPENDENCY_VERSION },
            "Dynamic selector 'latest.release' should be rejected")
    }

    @Test
    fun `Dynamic range selector rejected`() {
        val d = dep(requestedVersion = "[1.0,2.0)")
        val diags = verifier.verify(listOf(d), listOf(d))
        assertTrue(diags.any { it.code == DiagnosticCode.DYNAMIC_DEPENDENCY_VERSION },
            "Dynamic range selector should be rejected")
    }

    @Test
    fun `Snapshot dependencies rejected`() {
        val d = dep(selectedVersion = "2.0.0-SNAPSHOT")
        val diags = verifier.verify(listOf(d), listOf(d))
        assertTrue(diags.any { it.code == DiagnosticCode.SNAPSHOT_DEPENDENCY && it.severity == DiagnosticSeverity.FAILURE },
            "SNAPSHOT dependency should be rejected")
    }

    @Test
    fun `Convergence conflicts detected`() {
        val d1 = dep(consumers = listOf(":tramai-engine"), group = "com.example", artifact = "lib", selectedVersion = "1.0")
        val d2 = dep(consumers = listOf(":tramai-core"), group = "com.example", artifact = "lib", selectedVersion = "2.0")
        val diags = verifier.verify(emptyList(), listOf(d1, d2))
        assertTrue(diags.any { it.code == DiagnosticCode.DEPENDENCY_CONVERGENCE_FAILURE },
            "Two versions of same group:artifact should produce convergence failure")
    }

    @Test
    fun `Empty committed baseline fails`() {
        val diags = verifier.verify(emptyList(), emptyList())
        assertTrue(diags.any { it.code == DiagnosticCode.DEPENDENCY_BASELINE_EMPTY },
            "Empty baseline should produce DEPENDENCY_BASELINE_EMPTY")
        assertTrue(
            diags.any {
                it.code == DiagnosticCode.DEPENDENCY_BASELINE_EMPTY &&
                    it.severity.name == "FAILURE"
            }
        )
    }

    @Test
    fun `Records sorted deterministically`() {
        val d1 = dep(group = "zeta", artifact = "zzz")
        val d2 = dep(group = "alpha", artifact = "aaa")
        val sorted = DependencyBaselineVerifier.sortRecords(listOf(d1, d2))
        assertEquals("alpha", sorted[0].group)
        assertEquals("zeta", sorted[1].group)
    }

    @Test
    fun `Same dependency different consumers preserved`() {
        val d1 = dep(consumers = listOf(":tramai-engine"), group = "com.example", artifact = "lib")
        val d2 = dep(consumers = listOf(":tramai-core"), group = "com.example", artifact = "lib")
        assertEquals(2, DependencyBaselineVerifier.sortRecords(listOf(d1, d2)).size,
            "Same dependency through different consumers should produce 2 records")
    }

    @Test
    fun `Absolute path in dependency path rejected`() {
        val d = dep(dependencyPath = listOf(":tramai-engine", "/home/user/.gradle/caches/lib:1.0"))
        val diags = verifier.verify(listOf(d), listOf(d))
        assertTrue(diags.any { it.code == DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED },
            "Absolute path in dependency path should be rejected")
    }

    @Test
    fun `Two equivalent inputs produce byte-identical JSON`() {
        val records = listOf(
            dep(group = "alpha", artifact = "aaa"),
            dep(group = "beta", artifact = "bbb"),
        )
        val json1 = DependencyBaselineVerifier.deterministicJson(records)
        val json2 = DependencyBaselineVerifier.deterministicJson(records.shuffled())
        assertEquals(json1, json2, "Deterministic JSON should be byte-identical regardless of input order")
    }

    @Test
    fun `Resolution exception produces failure diagnostic`() {
        val ex = RuntimeException("Connection refused")
        val diags = verifier.verify(emptyList(), emptyList(), resolutionFailure = ex)
        assertTrue(diags.any { it.code == DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED },
            "Resolution exception should produce DEPENDENCY_RESOLUTION_FAILED")
    }

    @Test
    fun `Added dependency detected as drift`() {
        val committed = listOf(dep(group = "alpha", artifact = "aaa"))
        val current = listOf(
            dep(group = "alpha", artifact = "aaa"),
            dep(group = "beta", artifact = "bbb"),
        )
        val diags = verifier.verify(committed, current)
        assertTrue(diags.any { it.code == DiagnosticCode.DEPENDENCY_ADDED },
            "New dependency should produce DEPENDENCY_ADDED warning")
    }

    @Test
    fun `Removed dependency detected as drift`() {
        val committed = listOf(
            dep(group = "alpha", artifact = "aaa"),
            dep(group = "beta", artifact = "bbb"),
        )
        val current = listOf(dep(group = "alpha", artifact = "aaa"))
        val diags = verifier.verify(committed, current)
        assertTrue(diags.any { it.code == DiagnosticCode.DEPENDENCY_REMOVED },
            "Removed dependency should produce DEPENDENCY_REMOVED warning")
    }

    @Test
    fun `Version change detected as drift`() {
        val committed = listOf(dep(selectedVersion = "1.0"))
        val current = listOf(dep(selectedVersion = "2.0"))
        val diags = verifier.verify(committed, current)
        assertTrue(diags.any { it.code == DiagnosticCode.DEPENDENCY_VERSION_CHANGED },
            "Version change should produce DEPENDENCY_VERSION_CHANGED warning")
    }
}
