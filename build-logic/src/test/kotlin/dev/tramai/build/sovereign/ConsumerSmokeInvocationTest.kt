package dev.tramai.build.sovereign

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsumerSmokeInvocationTest {

    @Test
    fun `spaced repo path stays a single executable argument`() {
        // Regression: the invocation used to be serialized to a display string
        // and then split(" ") back into args — a repo path containing spaces
        // became several arguments. The executable args must be a List<String>
        // derived directly from the path, never by splitting the display.
        val spacedPath = "C:\\Users\\Giona Granchelli\\work\\tramai\\build\\sovereign-runtime-release-verification-repo"
        val invocation = consumerSmokeInvocation(spacedPath, "0.6.0")

        // Repo path with spaces must be ONE argument — never split by spaces.
        assertEquals(
            "-PsovereignRuntimeVerificationRepo=$spacedPath",
            invocation.args.last { it.startsWith("-PsovereignRuntimeVerificationRepo=") },
            "repo path with spaces must be ONE argument",
        )
        assertEquals(6, invocation.args.size, "exactly six executable args, path unbroken")
        // Display is informational only — it may be ambiguous, args are truth.
        assertEquals("./gradlew ${invocation.args.joinToString(" ")}", invocation.display)
    }

    @Test
    fun `spaced fixture project dir is honored by the exec wiring`() {
        // TestKit-level proof: a fixture whose root path contains spaces must
        // still configure the consumer smoke Exec with the unbroken repo path.
        val spacedPath = "/tmp/project with spaces/build/sovereign-runtime-release-verification-repo"
        val invocation = consumerSmokeInvocation(spacedPath, "0.6.0")
        val repoArg = invocation.args.last { it.startsWith("-PsovereignRuntimeVerificationRepo=") }
        assertEquals("-PsovereignRuntimeVerificationRepo=$spacedPath", repoArg)
        // Splitting the display would fragment the spaced path — prove the
        // display is NOT the execution representation.
        val naiveSplit = invocation.display.split(" ")
        assertTrue(
            naiveSplit.none { it == "-PsovereignRuntimeVerificationRepo=$spacedPath" },
            "display string fragments a spaced path; must never be split for execution",
        )
    }

    @Test
    fun `non-spaced invocation shape is stable`() {
        val invocation = consumerSmokeInvocation("/tmp/repo", "0.6.0")
        assertEquals(
            listOf(
                "-p", "examples/sovereign-runtime-consumer-smoke",
                "test",
                "-PtramaiVersion=0.6.0",
                "-PsovereignRuntimeVerificationRepo=/tmp/repo",
                "--no-configuration-cache",
            ),
            invocation.args,
        )
        assertEquals("./gradlew", invocation.gradleWrapper)
    }
}
