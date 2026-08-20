package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Regression tests for FindingIdentity.fromNondeterminism/fromCancellationCatch/fromGlobalState.
 *
 * The identity key must be *line-independent*: a finding that shifts lines (e.g. due to an
 * unrelated refactor) must not register as a new finding. This guards the string-template
 * precedence bug where `":$f.module"` interpolated the finding's `toString()` (which embeds
 * the line number) instead of its `module` property.
 */
class FindingIdentityTest {

    private fun nd(
        line: Int,
        file: String = "tramai-scheduler/src/main/kotlin/dev/tramai/scheduler/Timer.kt",
        source: String = "Instant.now()",
        category: String = "clock",
        module: String = "tramai-scheduler"
    ) = NondeterminismFinding(
        module = module, file = file, line = line,
        source = source, classification = "business_time", category = category
    )

    @Test
    fun `nondeterminism identity is independent of line number`() {
        val before = FindingIdentity.fromNondeterminism(nd(line = 32)).toIdentityKey()
        val after = FindingIdentity.fromNondeterminism(nd(line = 91)).toIdentityKey()
        assertEquals(before, after, "line shift must not change the identity key")
    }

    @Test
    fun `nondeterminism identity differs when source or category differs`() {
        val a = FindingIdentity.fromNondeterminism(nd(line = 1, source = "Instant.now()", category = "clock")).toIdentityKey()
        val b = FindingIdentity.fromNondeterminism(nd(line = 1, source = "UUID.randomUUID()", category = "identity")).toIdentityKey()
        assertNotEquals(a, b)
    }

    @Test
    fun `module without colon normalizes identically to module with colon`() {
        val bare = FindingIdentity.fromNondeterminism(nd(line = 1, module = "tramai-scheduler")).toIdentityKey()
        val colonized = FindingIdentity.fromNondeterminism(nd(line = 1, module = ":tramai-scheduler")).toIdentityKey()
        assertEquals(bare, colonized)
    }

    @Test
    fun `cancellation identity is independent of line number`() {
        val a = FindingIdentity.fromCancellationCatch(
            CancellationCatchFinding(module = "tramai-core", file = "A.kt", function = "foo", catchType = "Exception", risk = "medium")
        ).toIdentityKey()
        // fromCancellationCatch has no line field; key must be stable and not embed the object toString
        val b = FindingIdentity.fromCancellationCatch(
            CancellationCatchFinding(module = "tramai-core", file = "A.kt", function = "foo", catchType = "Exception", risk = "medium")
        ).toIdentityKey()
        assertEquals(a, b)
        assertEquals(b.split("::")[1], ":tramai-core", "modulePath must be the module, not the object toString")
    }
}
