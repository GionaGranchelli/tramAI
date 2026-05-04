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
class AuditControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
) {
    @Test
    fun `GET audit returns paginated response structure`() {
        val response = mockMvc.get("/audit")
            .andExpect {
                status { isOk() }
                content { contentType("application/json") }
            }
            .andReturn()
            .response
            .contentAsString

        val json = objectMapper.readTree(response)
        assertThat(json.has("entries")).isTrue()
        assertThat(json.has("totalCount")).isTrue()
        assertThat(json.has("page")).isTrue()
        assertThat(json.has("pageSize")).isTrue()
        assertThat(json.get("entries").isArray).isTrue()
    }

    @Test
    fun `GET audit with empty store returns zero entries`() {
        val response = mockMvc.get("/audit")
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString

        val json = objectMapper.readTree(response)
        assertThat(json.get("entries").size()).isEqualTo(0)
        assertThat(json.get("totalCount").asLong()).isEqualTo(0)
        assertThat(json.get("page").asInt()).isEqualTo(0)
        assertThat(json.get("pageSize").asInt()).isEqualTo(50)
    }

    @Test
    fun `GET audit with page and size params respects pagination`() {
        val response = mockMvc.get("/audit") {
            param("page", "0")
            param("size", "10")
        }
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString

        val json = objectMapper.readTree(response)
        assertThat(json.get("page").asInt()).isEqualTo(0)
        assertThat(json.get("pageSize").asInt()).isEqualTo(10)
        assertThat(json.get("entries").size()).isEqualTo(0)
        assertThat(json.get("totalCount").asLong()).isEqualTo(0)
    }

    @Test
    fun `GET audit with actor filter returns filtered results`() {
        val response = mockMvc.get("/audit") {
            param("actor", "admin")
        }
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString

        val json = objectMapper.readTree(response)
        assertThat(json.get("entries").isArray).isTrue()
        assertThat(json.get("totalCount").asLong()).isEqualTo(0)
    }

    @Test
    fun `GET audit with action filter returns filtered results`() {
        val response = mockMvc.get("/audit") {
            param("action", "workflow.run")
        }
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString

        val json = objectMapper.readTree(response)
        assertThat(json.get("entries").isArray).isTrue()
    }

    @Test
    fun `GET audit with time range filter returns filtered results`() {
        val response = mockMvc.get("/audit") {
            param("from", "2024-01-01T00:00:00Z")
            param("to", "2024-12-31T23:59:59Z")
        }
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString

        val json = objectMapper.readTree(response)
        assertThat(json.get("entries").isArray).isTrue()
    }

    @Test
    fun `GET audit with all filters combined returns valid response`() {
        val response = mockMvc.get("/audit") {
            param("actor", "system")
            param("action", "schedule.tick")
            param("from", "2024-01-01T00:00:00Z")
            param("to", "2024-12-31T23:59:59Z")
            param("page", "0")
            param("size", "25")
        }
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString

        val json = objectMapper.readTree(response)
        assertThat(json.get("entries").isArray).isTrue()
        assertThat(json.get("totalCount").asLong()).isEqualTo(0)
        assertThat(json.get("page").asInt()).isEqualTo(0)
        assertThat(json.get("pageSize").asInt()).isEqualTo(25)
    }

    @Test
    fun `GET audit rejects negative page with problem detail`() {
        mockMvc.get("/audit") {
            param("page", "-1")
        }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.type") { value("https://tramai.dev/problems/400") }
                jsonPath("$.title") { value("Invalid workflow request") }
                jsonPath("$.status") { value(400) }
            }
    }

    @Test
    fun `GET audit rejects oversized page size with problem detail`() {
        mockMvc.get("/audit") {
            param("size", "101")
        }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.type") { value("https://tramai.dev/problems/400") }
                jsonPath("$.title") { value("Invalid workflow request") }
                jsonPath("$.status") { value(400) }
            }
    }

    @Test
    fun `GET audit rejects invalid timestamps with problem detail`() {
        mockMvc.get("/audit") {
            param("from", "not-an-instant")
        }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.type") { value("https://tramai.dev/problems/400") }
                jsonPath("$.title") { value("Invalid workflow request") }
                jsonPath("$.status") { value(400) }
            }
    }

    @Test
    fun `audit entries have correct field structure`() {
        val response = mockMvc.get("/audit")
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString

        val json = objectMapper.readTree(response)
        json.get("entries").forEach { entry ->
            assertThat(entry.has("timestamp")).isTrue()
            assertThat(entry.has("actor")).isTrue()
            assertThat(entry.has("action")).isTrue()
            assertThat(entry.has("resourceType")).isTrue()
            assertThat(entry.has("resourceId")).isTrue()
            assertThat(entry.has("status")).isTrue()
            assertThat(entry.has("metadata")).isTrue()
        }
    }
}
