package dev.tramai.spring.compatibility

import dev.tramai.sovereign.SovereignTramai
import dev.tramai.sovereign.SovereignTramaiRuntime
import dev.tramai.standalone.Tramai
import org.junit.jupiter.api.Test

/**
 * Runs the S7 profile compatibility contract against both runtime profiles.
 *
 * The SAME application fixture (TckCompatibilityFixture) is executed twice;
 * the only semantic selection difference between the two runs is
 * `tramai.profile` plus profile-specific runtime configuration. This is the
 * key S0–S6 compatibility proof: one programming model, two runtime
 * profiles, different guarantees.
 */
class SpringRuntimeProfileCompatibilityTckTest {

    @Test
    fun `standard profile conforms to the Spring compatibility contract`() {
        SpringRuntimeProfileCompatibilityContract(
            RuntimeProfileHarness(
                displayName = "standard",
                profileProperty = "tramai.profile=standard",
                runtimeType = Tramai::class.java,
                forbiddenRuntimeType = SovereignTramai::class.java,
                sovereignRuntimeType = null,
                properties = arrayOf(
                    "tramai.models.local-model=local-provider",
                    "tramai.default-provider=local-provider",
                ),
            ),
        ).verify()
    }

    @Test
    fun `sovereign profile conforms to the Spring compatibility contract`() {
        SpringRuntimeProfileCompatibilityContract(
            RuntimeProfileHarness(
                displayName = "sovereign",
                profileProperty = "tramai.profile=sovereign",
                runtimeType = SovereignTramai::class.java,
                forbiddenRuntimeType = Tramai::class.java,
                sovereignRuntimeType = SovereignTramaiRuntime::class.java,
                properties = arrayOf(
                    "tramai.sovereign.allowed-models[0]=local-model",
                    "tramai.sovereign.allowed-providers[0]=local-provider",
                    "tramai.sovereign.provider-zones.local-provider=LOCAL",
                    "tramai.sovereign.models.local-model=local-provider",
                ),
            ),
        ).verify()
    }
}
