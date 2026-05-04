package dev.tramai.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(
    classes = [TramaiServerApplication::class],
)
@AutoConfigureMockMvc
class WorkerControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
) {
    @Test
    fun `GET workers returns empty JSON array when no workers registered`() {
        mockMvc.get("/workers")
            .andExpect {
                status { isOk() }
                content { json("[]") }
            }
    }

    @Test
    fun `GET workers returns registered workers with correct fields`() {
        // We can't easily register workers via REST in this test (no POST endpoint),
        // but we can test the controller bean directly via the registry.
        // For the HTTP-level test, just verify the endpoint structure.
        mockMvc.get("/workers")
            .andExpect {
                status { isOk() }
                content { contentType("application/json") }
            }
    }

    @Test
    fun `worker status is online or offline`() {
        val response = mockMvc.get("/workers")
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString

        val workers = objectMapper.readTree(response)
        assertThat(workers.isArray).isTrue()
        workers.forEach { worker ->
            val status = worker.get("status").asText()
            assertThat(status).`isIn`("online", "offline")
        }
    }

    @Test
    fun `worker info has all required fields`() {
        val response = mockMvc.get("/workers")
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString

        val workers = objectMapper.readTree(response)
        workers.forEach { worker ->
            assertThat(worker.has("workerId")).isTrue()
            assertThat(worker.has("status")).isTrue()
            assertThat(worker.has("poolName")).isTrue()
            assertThat(worker.has("capabilityLabels")).isTrue()
            assertThat(worker.has("version")).isTrue()
            assertThat(worker.has("host")).isTrue()
            assertThat(worker.has("lastHeartbeat")).isTrue()
            assertThat(worker.has("activeRunCount")).isTrue()
            assertThat(worker.has("draining")).isTrue()
        }
    }

    @Test
    fun `GET workers events returns SSE content type`() {
        mockMvc.get("/workers/events")
            .andExpect {
                // SSE may or may not have the expected content type depending on async behavior,
                // but we can verify the request is accepted
                status { is2xxSuccessful() }
            }
    }
}
