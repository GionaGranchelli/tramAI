package dev.tramai.core.observation.event

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RuntimeEventCatalogueTest {
    @Test
    fun `every event name is unique`() {
        val names = RuntimeEventCatalogue.allEvents.map { it.name }
        assertThat(names).doesNotHaveDuplicates()
    }

    @Test
    fun `every event belongs to one domain and declares all metadata`() {
        for (event in RuntimeEventCatalogue.allEvents) {
            assertThat(event.name).isNotBlank()
            assertThat(event.sensitivity).isNotNull()
            assertThat(event.requiredAttributes).allMatch { it in event.allowedAttributes }
            // audit/evidence/span/failure-policy are explicit booleans/enums; only
            // ensure no event forgot its domain.
            assertThat(event.domain).isNotNull()
        }
    }

    @Test
    fun `every attribute name has exactly one canonical type`() {
        val byName = RuntimeEventCatalogue.allEvents
            .flatMap { it.allowedAttributes }
            .groupBy { it.name }
        for ((name, keys) in byName) {
            assertThat(keys.map { it.valueType }.distinct())
                .withFailMessage("Attribute '$name' has conflicting canonical types")
                .hasSize(1)
        }
    }

    @Test
    fun `catalogue is deterministic`() {
        // Repeated access yields identical, stable definitions in the same order.
        assertThat(RuntimeEventCatalogue.allEvents).isEqualTo(RuntimeEventCatalogue.allEvents)
        assertThat(RuntimeEventCatalogue.allEvents.map { it.name })
            .isEqualTo(RuntimeEventCatalogue.allEvents.map { it.name })
    }

    @Test
    fun `all metric mappings are declared`() {
        val declared = RuntimeMetrics.all.map { it.name }.toSet()
        val mapped = RuntimeEventCatalogue.allEvents.mapNotNull { it.metricMapping?.name }.toSet()
        assertThat(mapped).allMatch { it in declared }
    }
}
