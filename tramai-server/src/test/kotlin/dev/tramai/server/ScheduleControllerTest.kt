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
class ScheduleControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
) {
    @Test
    fun `GET schedules returns JSON array`() {
        mockMvc.get("/schedules")
            .andExpect {
                status { isOk() }
                content { contentType("application/json") }
            }
    }

    @Test
    fun `GET schedules returns empty array when no schedules configured`() {
        val response = mockMvc.get("/schedules")
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString

        val schedules = objectMapper.readTree(response)
        assertThat(schedules.isArray).isTrue()
        assertThat(schedules.size()).isEqualTo(0)
    }

    @Test
    fun `schedule entries have correct field structure when present`() {
        val response = mockMvc.get("/schedules")
            .andExpect {
                status { isOk() }
            }
            .andReturn()
            .response
            .contentAsString

        val schedules = objectMapper.readTree(response)
        assertThat(schedules.isArray).isTrue()
        schedules.forEach { schedule ->
            assertThat(schedule.has("workflowName")).isTrue()
            assertThat(schedule.has("cronExpression")).isTrue()
            assertThat(schedule.has("nextTick")).isTrue()
            assertThat(schedule.has("lastTick")).isTrue()
            assertThat(schedule.has("lastRunStatus")).isTrue()
            assertThat(schedule.has("lastRunId")).isTrue()
            assertThat(schedule.has("misfireCount")).isTrue()
        }
    }

    @Test
    fun `GET schedules events returns successful response`() {
        mockMvc.get("/schedules/events")
            .andExpect {
                status { is2xxSuccessful() }
            }
    }
}
