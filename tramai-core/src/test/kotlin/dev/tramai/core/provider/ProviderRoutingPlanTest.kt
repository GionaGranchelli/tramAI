package dev.tramai.core.provider

import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class ProviderRoutingPlanTest {
    @Test
    fun `primary and fallbacks preserve registration order`() {
        val plan = ProviderRoutingPlan.builder()
            .provider("primary", NamedProvider("primary"))
            .provider("first", NamedProvider("first"))
            .provider("second", NamedProvider("second"))
            .model("requested", "primary")
            .fallbackModel("requested", "first-model", "first")
            .fallbackModel("requested", "second-model", "second")
            .build()

        assertThat(plan.providers.keys.map { it.value }).containsExactly("primary", "first", "second")
        assertThat(plan.routes.getValue(ModelId("requested"))).containsExactly(
            PlannedProviderRoute(ProviderId("primary"), ModelId("requested")),
            PlannedProviderRoute(ProviderId("first"), ModelId("first-model")),
            PlannedProviderRoute(ProviderId("second"), ModelId("second-model")),
        )
    }

    @Test fun `duplicate provider is rejected at build`() = assertThatThrownBy {
        ProviderRoutingPlan.builder().provider("one", NamedProvider("one")).provider("one", NamedProvider("two")).build()
    }.isInstanceOf(ConfigurationException::class.java)

    @Test fun `blank provider and model ids are rejected`() {
        assertThatThrownBy { ProviderRoutingPlan.builder().provider(" ", NamedProvider("one")).build() }
            .isInstanceOf(ConfigurationException::class.java)
        assertThatThrownBy { ProviderRoutingPlan.builder().provider("one", NamedProvider("one")).model(" ", "one").build() }
            .isInstanceOf(ConfigurationException::class.java)
    }

    @Test fun `unknown primary provider is rejected at build`() = assertThatThrownBy {
        ProviderRoutingPlan.builder().model("model", "missing").build()
    }.isInstanceOf(ConfigurationException::class.java)

    @Test fun `unknown fallback provider is rejected at build`() = assertThatThrownBy {
        ProviderRoutingPlan.builder().provider("one", NamedProvider("one")).model("model", "one").fallbackProvider("model", "missing").build()
    }.isInstanceOf(ConfigurationException::class.java)

    @Test fun `unknown default provider is rejected at build`() = assertThatThrownBy {
        ProviderRoutingPlan.builder().defaultProvider("missing").build()
    }.isInstanceOf(ConfigurationException::class.java)

    @Test fun `duplicate identical fallback route is rejected`() = assertThatThrownBy {
        ProviderRoutingPlan.builder().provider("one", NamedProvider("one")).model("model", "one")
            .fallbackModel("model", "fallback", "one").fallbackModel("model", "fallback", "one").build()
    }.isInstanceOf(ConfigurationException::class.java)

    @Test
    fun `plan snapshot is unaffected by later builder mutation`() {
        val builder = ProviderRoutingPlan.builder().provider("one", NamedProvider("one")).model("model", "one")
        val plan = builder.build()
        builder.provider("two", NamedProvider("two")).fallbackProvider("model", "two")

        assertThat(plan.providers.keys).containsExactly(ProviderId("one"))
        assertThat(plan.routes.getValue(ModelId("model"))).containsExactly(
            PlannedProviderRoute(ProviderId("one"), ModelId("model")),
        )
    }

    @Test
    fun `provider collaborator identity is preserved`() {
        val provider = NamedProvider("one")
        val plan = ProviderRoutingPlan.builder().provider("one", provider).build()
        assertThat(plan.providers.getValue(ProviderId("one"))).isSameAs(provider)
    }

    @Test
    fun `fallback provider for same model is accepted`() {
        val plan = ProviderRoutingPlan.builder().provider("one", NamedProvider("one")).provider("two", NamedProvider("two"))
            .model("model", "one").fallbackProvider("model", "two").build()
        assertThat(plan.routes.getValue(ModelId("model"))).hasSize(2)
    }

    @Test
    fun `plan collections reject mutation after build`() {
        val plan = ProviderRoutingPlan.builder()
            .provider("one", NamedProvider("one"))
            .model("model", "one")
            .build()

        assertThatThrownBy {
            (plan.providers as MutableMap<ProviderId, ModelProvider>)[ProviderId("x")] = NamedProvider("x")
        }.isInstanceOf(UnsupportedOperationException::class.java)

        assertThatThrownBy {
            (plan.routes[ModelId("model")] as MutableList<PlannedProviderRoute>).add(
                PlannedProviderRoute(ProviderId("x"), ModelId("x")),
            )
        }.isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `fallback routes without a primary are rejected at build`() = assertThatThrownBy {
        ProviderRoutingPlan.builder().provider("one", NamedProvider("one"))
            .fallbackModel("model", "alternate", "one").build()
    }.isInstanceOf(ConfigurationException::class.java)
        .hasMessageContaining("no primary route")

    @Test
    fun `duplicate primary route is rejected`() = assertThatThrownBy {
        ProviderRoutingPlan.builder().provider("one", NamedProvider("one")).provider("two", NamedProvider("two"))
            .model("model", "one").model("model", "two").build()
    }.isInstanceOf(ConfigurationException::class.java)
        .hasMessageContaining("Duplicate primary route")

    @Test
    fun `fallback identical to primary is rejected`() = assertThatThrownBy {
        ProviderRoutingPlan.builder().provider("one", NamedProvider("one"))
            .model("model", "one").fallbackProvider("model", "one").build()
    }.isInstanceOf(ConfigurationException::class.java)
        .hasMessageContaining("duplicates its primary route")

    @Test
    fun `model ids with surrounding whitespace are rejected`() {
        assertThatThrownBy {
            ProviderRoutingPlan.builder().provider("one", NamedProvider("one")).model(" model ", "one").build()
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("whitespace")
        assertThatThrownBy {
            ProviderRoutingPlan.builder().provider("one", NamedProvider("one")).model("model", "one")
                .fallbackModel("model", " fallback ", "one").build()
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("whitespace")
    }

    @Test
    fun `fallback registered before primary is preserved`() {
        val plan = ProviderRoutingPlan.builder()
            .provider("one", NamedProvider("one"))
            .provider("two", NamedProvider("two"))
            .fallbackProvider("model", "two")
            .model("model", "one")
            .build()
        assertThat(plan.routes.getValue(ModelId("model"))).containsExactly(
            PlannedProviderRoute(ProviderId("one"), ModelId("model")),
            PlannedProviderRoute(ProviderId("two"), ModelId("model")),
        )
    }

    private class NamedProvider(private val name: String) : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse = error("unused")
        override fun providerId(): String = name
    }
}
