package dev.tramai.security.governance

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.ProviderRoutingPlan

/**
 * A registered provider stub for governance tests.
 *
 * Capability support is the only fact the authorization boundary consults about a provider, so the
 * stub answers exactly that and nothing else: [complete] is unreachable because authorization
 * performs no invocation.
 */
internal class StubProvider(
    private val supported: Set<ProviderCapability>,
) : ModelProvider {
    override fun supportsCapability(capability: ProviderCapability): Boolean = capability in supported

    override suspend fun complete(request: ModelRequest): ModelResponse = error("governance tests never invoke")
}

/**
 * A registration snapshot registering each named provider with the capabilities it supports.
 *
 * This builds the authoritative registration a candidate is checked against; it does not model
 * routing, and nothing here may become a second registry.
 */
internal fun registrationPlan(vararg providers: Pair<String, Set<ProviderCapability>>): ProviderRoutingPlan {
    val builder = ProviderRoutingPlan.builder()
    providers.forEach { (name, capabilities) -> builder.provider(name, StubProvider(capabilities)) }
    return builder.build()
}
