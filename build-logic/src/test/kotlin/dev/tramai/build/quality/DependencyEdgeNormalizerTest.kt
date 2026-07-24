package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies deterministic dependency-edge normalization, including preservation
 * of distinct parent edges in diamond-shaped dependency graphs.
 */
class DependencyEdgeNormalizerTest {

    @Test
    fun `diamond graph produces identical normalized records`() {
        // Simulate a diamond: consumer -> A -> C, consumer -> B -> C -> D
        val testRecords = listOf(
            // Consumer -> A (direct)
            ResolvedDependency(
                group = "com.example", artifact = "lib-a", selectedVersion = "1.0",
                requestedVersion = "1.0", direct = true,
                configuration = "runtimeClasspath",
                selectionReason = "requested",
                dependencyPath = listOf(":consumer", "com.example:lib-a:1.0"),
                consumers = listOf(":consumer")
            ),
            // A depends on C (transitive)
            ResolvedDependency(
                group = "com.example", artifact = "lib-c", selectedVersion = "2.0",
                requestedVersion = "2.0", direct = false,
                configuration = "runtimeClasspath",
                selectionReason = "requested",
                dependencyPath = listOf(":consumer", "com.example:lib-a:1.0", "com.example:lib-c:2.0"),
                consumers = listOf(":consumer")
            ),
            // Consumer -> B (direct)
            ResolvedDependency(
                group = "com.example", artifact = "lib-b", selectedVersion = "1.5",
                requestedVersion = "1.5", direct = true,
                configuration = "runtimeClasspath",
                selectionReason = "requested",
                dependencyPath = listOf(":consumer", "com.example:lib-b:1.5"),
                consumers = listOf(":consumer")
            ),
            // B depends on C (transitive) — same C as above
            ResolvedDependency(
                group = "com.example", artifact = "lib-c", selectedVersion = "2.0",
                requestedVersion = "2.0", direct = false,
                configuration = "runtimeClasspath",
                selectionReason = "requested",
                dependencyPath = listOf(":consumer", "com.example:lib-b:1.5", "com.example:lib-c:2.0"),
                consumers = listOf(":consumer")
            ),
            // C depends on D (transitive)
            ResolvedDependency(
                group = "com.example", artifact = "lib-d", selectedVersion = "0.5",
                requestedVersion = "0.5", direct = false,
                configuration = "runtimeClasspath",
                selectionReason = "requested",
                dependencyPath = listOf(":consumer", "com.example:lib-b:1.5", "com.example:lib-c:2.0", "com.example:lib-d:0.5"),
                consumers = listOf(":consumer")
            )
        )

        // Apply edge deduplication via the shared normalizer
        val deduped = DependencyEdgeNormalizer.normalize(testRecords)

        // Should preserve A->C and B->C, and C->D once
        val byParentAndArtifact = deduped.groupBy { 
            it.dependencyPath.dropLast(1).lastOrNull().orEmpty() to it.artifact 
        }
        
        // A->C should exist
        assertTrue(byParentAndArtifact.containsKey("com.example:lib-a:1.0" to "lib-c"),
            "A->C edge should be preserved")
        // B->C should exist
        assertTrue(byParentAndArtifact.containsKey("com.example:lib-b:1.5" to "lib-c"),
            "B->C edge should be preserved")
        // C->D should exist exactly once
        val cToD = byParentAndArtifact.filterKeys { (parent) -> parent.contains("lib-c") }
        assertEquals(1, cToD.size, "C->D should exist exactly once")
    }

    @Test
    fun `dedup preserves consumer and configuration`() {
        val records = listOf(
            ResolvedDependency(
                group = "com.example", artifact = "lib-x", selectedVersion = "1.0",
                requestedVersion = "1.0", direct = true,
                configuration = "runtimeClasspath",
                selectionReason = "requested",
                dependencyPath = listOf(":consumer1", "com.example:lib-x:1.0"),
                consumers = listOf(":consumer1")
            ),
            ResolvedDependency(
                group = "com.example", artifact = "lib-x", selectedVersion = "1.0",
                requestedVersion = "1.0", direct = true,
                configuration = "compileClasspath",
                selectionReason = "requested",
                dependencyPath = listOf(":consumer1", "com.example:lib-x:1.0"),
                consumers = listOf(":consumer1")
            )
        )
        val deduped = DependencyEdgeNormalizer.normalize(records)
        assertEquals(2, deduped.size, "Different configurations should NOT be deduplicated")
    }
}
