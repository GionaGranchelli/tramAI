package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Regression tests for the FindingIdentity from* factories.
 *
 * Identity keys must be *line-independent* (and independent of any measured
 * value that participates in a finding's toString()): a finding that shifts
 * lines or changes its measured value must not register as a new finding.
 * This guards the string-template precedence bug where `":$f.module"`
 * interpolated the finding's `toString()` (which embeds the line/value)
 * instead of its `module` property.
 */
class FindingIdentityTest {

    // ─── Factory helpers ───

    private fun catchFinding(
        sourceLine: Int,
        module: String = "tramai-scheduler",
        file: String = "tramai-scheduler/src/main/kotlin/dev/tramai/scheduler/Timer.kt",
        function: String = "tick",
        catchType: String = "Exception"
    ) = CancellationCatchFinding(
        module = module, file = file, function = function,
        catchType = catchType, risk = "medium", sourceLine = sourceLine
    )

    private fun globalFinding(
        module: String = "tramai-engine",
        file: String = "tramai-engine/src/main/kotlin/dev/tramai/engine/Engine.kt",
        declaration: String = "Engine",
        kind: String = "mutable-state"
    ) = GlobalStateFinding(
        module = module, file = file, declaration = declaration,
        kind = kind, type = "Boolean", mutable = true
    )

    private fun ndFinding(
        line: Int,
        module: String = "tramai-scheduler",
        file: String = "tramai-scheduler/src/main/kotlin/dev/tramai/scheduler/Timer.kt",
        source: String = "Instant.now()",
        category: String = "clock"
    ) = NondeterminismFinding(
        module = module, file = file, line = line,
        source = source, classification = "business_time", category = category
    )

    private fun hotspot(
        value: Int,
        module: String = "tramai-engine",
        path: String = "tramai-engine/src/main/kotlin/dev/tramai/engine/Engine.kt",
        declaration: String = "run",
        metric: String = "cyclomaticComplexity"
    ) = StructuralHotspot(
        module = module, path = path, declaration = declaration,
        metric = metric, value = value
    )

    // ─── Identity stability (the regression) ───

    @Test
    fun `cancellation identity is independent of sourceLine`() {
        assertEquals(
            FindingIdentity.fromCancellationCatch(catchFinding(sourceLine = 20)).toIdentityKey(),
            FindingIdentity.fromCancellationCatch(catchFinding(sourceLine = 200)).toIdentityKey()
        )
    }

    @Test
    fun `global state identity is stable for the same semantic finding`() {
        assertEquals(
            FindingIdentity.fromGlobalState(globalFinding()).toIdentityKey(),
            FindingIdentity.fromGlobalState(globalFinding()).toIdentityKey()
        )
    }

    @Test
    fun `nondeterminism identity is independent of line`() {
        assertEquals(
            FindingIdentity.fromNondeterminism(ndFinding(line = 32)).toIdentityKey(),
            FindingIdentity.fromNondeterminism(ndFinding(line = 91)).toIdentityKey()
        )
    }

    @Test
    fun `structural hotspot identity is independent of value`() {
        // value participates in StructuralHotspot.toString(); a change must not churn identity
        assertEquals(
            FindingIdentity.fromStructuralHotspot(hotspot(value = 4)).toIdentityKey(),
            FindingIdentity.fromStructuralHotspot(hotspot(value = 723)).toIdentityKey()
        )
    }

    @Test
    fun `module without colon normalizes identically to module with colon for all factories`() {
        for (keyOf in listOf(
            { m: String -> FindingIdentity.fromCancellationCatch(catchFinding(sourceLine = 1, module = m)).toIdentityKey() },
            { m: String -> FindingIdentity.fromGlobalState(globalFinding(module = m)).toIdentityKey() },
            { m: String -> FindingIdentity.fromNondeterminism(ndFinding(line = 1, module = m)).toIdentityKey() },
            { m: String -> FindingIdentity.fromStructuralHotspot(hotspot(value = 1, module = m)).toIdentityKey() }
        )) {
            assertEquals(keyOf("tramai-scheduler"), keyOf(":tramai-scheduler"))
        }
    }

    // ─── Identity must not be too coarse ───

    @Test
    fun `nondeterminism identity differs when source or category differs`() {
        val a = FindingIdentity.fromNondeterminism(ndFinding(line = 1, source = "Instant.now()", category = "clock")).toIdentityKey()
        val b = FindingIdentity.fromNondeterminism(ndFinding(line = 1, source = "UUID.randomUUID()", category = "identity")).toIdentityKey()
        assertNotEquals(a, b)
    }

    @Test
    fun `identity differs across modules`() {
        val scheduler = FindingIdentity.fromNondeterminism(ndFinding(line = 1, module = "tramai-scheduler")).toIdentityKey()
        val server = FindingIdentity.fromNondeterminism(ndFinding(line = 1, module = "tramai-server")).toIdentityKey()
        assertNotEquals(scheduler, server)
    }

    @Test
    fun `modulePath is the module, not the object toString`() {
        val key = FindingIdentity.fromCancellationCatch(catchFinding(sourceLine = 20)).toIdentityKey()
        assertEquals(":tramai-scheduler", key.split("::")[1], "modulePath must be the module, not the object toString")
    }
}
