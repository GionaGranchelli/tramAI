package dev.tramai.security.governance

import dev.tramai.security.ProviderTrustZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Contract tests for the 0.7.2b provider-deployment / named trust-zone boundary.
 *
 * Audits ownership only: that a deployment owns exactly one named zone, that
 * brands carry no trust, and that a name resolves to exactly one category or to
 * nothing at all.
 */
class ProviderDeploymentTest {
    private val euWest = NamedTrustZone(TrustZoneName("eu-west-sovereign"), ProviderTrustZone.LOCAL)
    private val globalCloud = NamedTrustZone(TrustZoneName("global-cloud-approved"), ProviderTrustZone.GLOBAL_CLOUD)

    @Test
    fun `a deployment owns exactly one named trust zone`() {
        val deployment = ProviderDeployment("ollama-amsterdam-01", "ollama", euWest)

        assertEquals("ollama-amsterdam-01", deployment.deploymentId)
        assertEquals(TrustZoneName("eu-west-sovereign"), deployment.trustZone.name)
        assertEquals(ProviderTrustZone.LOCAL, deployment.trustZone.category)
    }

    @Test
    fun `two deployments of the same provider brand in different zones stay distinct`() {
        val local = ProviderDeployment("deployment-01", "openai", euWest)
        val remote = ProviderDeployment("deployment-02", "openai", globalCloud)

        assertEquals(local.providerId, remote.providerId)
        assertNotEquals(local.trustZone.category, remote.trustZone.category)
        assertNotEquals(local, remote)
    }

    @Test
    fun `deployments differing only by deployment identity are distinct`() {
        val first = ProviderDeployment("eu-west-amsterdam-01", "openai", euWest)
        val second = ProviderDeployment("eu-central-frankfurt-01", "openai", euWest)

        assertNotEquals(first, second)
    }

    @Test
    fun `the catalogue resolves a defined name to its category`() {
        val catalogue = TrustZoneCatalogue(listOf(euWest, globalCloud))

        assertEquals(ProviderTrustZone.LOCAL, catalogue.categoryOf(TrustZoneName("eu-west-sovereign")))
        assertEquals(ProviderTrustZone.GLOBAL_CLOUD, catalogue.categoryOf(TrustZoneName("global-cloud-approved")))
    }

    @Test
    fun `the catalogue resolves an unknown name to nothing rather than widening`() {
        val catalogue = TrustZoneCatalogue(listOf(euWest))

        assertNull(catalogue.categoryOf(TrustZoneName("eu-central-frankfurt-01")))
    }

    @Test
    fun `zone names are matched exactly, not case-insensitively`() {
        val catalogue = TrustZoneCatalogue(listOf(euWest))

        assertNull(catalogue.categoryOf(TrustZoneName("EU-WEST-SOVEREIGN")))
    }

    @Test
    fun `defining one name with two categories is rejected`() {
        val conflicting = NamedTrustZone(TrustZoneName("eu-west-sovereign"), ProviderTrustZone.GLOBAL_CLOUD)

        assertThrows(IllegalArgumentException::class.java) {
            TrustZoneCatalogue(listOf(euWest, conflicting))
        }
    }

    @Test
    fun `defining the same name twice with the same category is accepted`() {
        val repeat = NamedTrustZone(TrustZoneName("eu-west-sovereign"), ProviderTrustZone.LOCAL)

        val catalogue = TrustZoneCatalogue(listOf(euWest, repeat))

        assertEquals(ProviderTrustZone.LOCAL, catalogue.categoryOf(TrustZoneName("eu-west-sovereign")))
    }

    @Test
    fun `an undeclared catalogue resolves nothing`() {
        val catalogue = TrustZoneCatalogue(emptyList())

        assertNull(catalogue.categoryOf(TrustZoneName("eu-west-sovereign")))
    }

    @Test
    fun `a blank or untrimmed identity is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderDeployment("  ", "ollama", euWest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderDeployment("deployment-01", "", euWest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderDeployment(" deployment-01", "ollama", euWest)
        }
    }

    @Test
    fun `a blank, untrimmed or control-character zone name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TrustZoneName(" ") }
        assertThrows(IllegalArgumentException::class.java) { TrustZoneName("eu-west-sovereign ") }
        // BEL is not whitespace, so trim() does not catch it: this case isolates the
        // control-character rule from the blank and untrimmed rules.
        assertThrows(IllegalArgumentException::class.java) { TrustZoneName("eu-west\u0007sovereign") }
    }
}
