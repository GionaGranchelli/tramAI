package dev.tramai.platform

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PluginStateRepositoryTest {
    @Test
    fun `upsert inserts and updates plugin state without vendor specific sql`() {
        val repository = PluginStateRepository(platformDataSource("plugin-state"))

        repository.upsert(
            PluginStateRecord(
                id = "demo-plugin",
                version = "1.0.0",
                jarPath = "/plugins/demo-plugin.jar",
                enabled = true,
                status = PluginStatus.ENABLED,
                error = null,
            ),
        )
        repository.upsert(
            PluginStateRecord(
                id = "demo-plugin",
                version = "1.1.0",
                jarPath = "/plugins/demo-plugin-v2.jar",
                enabled = false,
                status = PluginStatus.DISABLED,
                error = "disabled for maintenance",
            ),
        )

        val stored = repository.find("demo-plugin")

        assertThat(stored).isNotNull
        assertThat(stored!!.version).isEqualTo("1.1.0")
        assertThat(stored.jarPath).isEqualTo("/plugins/demo-plugin-v2.jar")
        assertThat(stored.enabled).isFalse()
        assertThat(stored.status).isEqualTo(PluginStatus.DISABLED)
        assertThat(stored.error).isEqualTo("disabled for maintenance")
    }
}
