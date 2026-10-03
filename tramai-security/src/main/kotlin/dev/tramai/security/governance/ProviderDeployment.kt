package dev.tramai.security.governance

import dev.tramai.security.ProviderTrustZone

/**
 * Organization-defined name of a trust zone, for example `eu-west-sovereign`.
 *
 * Names are exact and case-sensitive: `EU-West` and `eu-west` are different
 * zones. A lookup is therefore never a fuzzy match, so a typo cannot silently
 * resolve to a different trust boundary.
 */
data class TrustZoneName(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "TrustZoneName must not be blank" }
        require(value == value.trim()) { "TrustZoneName must not contain leading or trailing whitespace" }
        require(value.none(Char::isISOControl)) { "TrustZoneName must not contain control characters" }
    }
}

/**
 * One organization-defined trust zone: a [name] bound to exactly one portable
 * [category].
 *
 * The category is what policy reasons over; the name is how an organization
 * describes its own topology. Binding both in one value means a zone cannot be
 * defined with two categories, so this type cannot express a conflicting
 * definition.
 */
data class NamedTrustZone(
    val name: TrustZoneName,
    val category: ProviderTrustZone,
)

/**
 * One concrete deployment of a provider, owning exactly one [NamedTrustZone].
 *
 * A provider brand proves nothing about trust: residency and locality follow
 * from where a deployment actually runs, not from who publishes the model. Two
 * deployments of the same [providerId] in different zones are therefore
 * different deployments, and they do not collapse into one another.
 *
 * "Exactly one" is structural — there is one `trustZone` field, so a deployment
 * cannot carry two zones or none.
 *
 * This type states ownership only. Whether a deployment may be used for a given
 * classification is a policy decision and deliberately not modelled here.
 */
data class ProviderDeployment(
    val deploymentId: String,
    val providerId: String,
    val trustZone: NamedTrustZone,
) {
    init {
        require(deploymentId.isNotBlank()) { "ProviderDeployment deploymentId must not be blank" }
        require(deploymentId == deploymentId.trim()) {
            "ProviderDeployment deploymentId must not contain leading or trailing whitespace"
        }
        require(providerId.isNotBlank()) { "ProviderDeployment providerId must not be blank" }
        require(providerId == providerId.trim()) {
            "ProviderDeployment providerId must not contain leading or trailing whitespace"
        }
    }
}

/**
 * The trust zones an organization defines.
 *
 * Construction enforces that a name maps to exactly one category: defining the
 * same name twice with the same category is idempotent, and defining it twice
 * with different categories fails loudly rather than letting one definition
 * silently win.
 *
 * [categoryOf] returns `null` for a name this catalogue does not define. Trust
 * is never inferred from absence, so an unknown name is the caller's cue to
 * refuse, not to fall back to a wider zone.
 */
class TrustZoneCatalogue(
    zones: Collection<NamedTrustZone>,
) {
    private val byName: Map<TrustZoneName, ProviderTrustZone>

    init {
        val conflicting =
            zones
                .groupBy({ it.name }, { it.category })
                .filterValues { categories -> categories.distinct().size > 1 }
        require(conflicting.isEmpty()) {
            "trust zone names defined with two categories: ${conflicting.keys.map { it.value }}"
        }
        byName = zones.associate { it.name to it.category }
    }

    fun categoryOf(name: TrustZoneName): ProviderTrustZone? = byName[name]
}
