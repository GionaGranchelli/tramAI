package dev.tramai.spring

import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class TramaiRuntimeProfileEnvironmentPostProcessorTest {

    private val postProcessor = TramaiRuntimeProfileEnvironmentPostProcessor()

    @Test
    fun `missing profile remains valid`() {
        postProcessor.postProcessEnvironment(StandardEnvironment(), SpringApplication())
    }

    @Test
    fun `supported profiles remain valid`() {
        listOf("standard", "sovereign").forEach { profile ->
            val environment = StandardEnvironment().apply {
                propertySources.addFirst(
                    MapPropertySource("test", mapOf("tramai.profile" to profile)),
                )
            }

            postProcessor.postProcessEnvironment(environment, SpringApplication())
        }
    }

    @Test
    fun `unsupported profile fails boot environment processing`() {
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource("test", mapOf("tramai.profile" to "soveriegn")),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            postProcessor.postProcessEnvironment(environment, SpringApplication())
        }
    }
}
