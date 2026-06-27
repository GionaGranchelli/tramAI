package dev.tramai.spring.sovereign.ops.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [ApprovalReviewerUiController::class])
@AutoConfigureMockMvc
class ApprovalReviewerUiControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `GET reviewer returns 200 with content type text html`() {
        mockMvc.perform(get("/tramai/sovereign/reviewer"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
    }

    @Test
    fun `HTML contains title and preview warning`() {
        val body = mockMvc.perform(get("/tramai/sovereign/reviewer"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        assertThat(body)
            .contains("TramAI Sovereign Approval Reviewer")
            .contains("Preview UI")
            .contains("Disabled by default")
    }

    @Test
    fun `HTML does not contain sensitive field names`() {
        val body = mockMvc.perform(get("/tramai/sovereign/reviewer"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        assertThat(body)
            .doesNotContain("resumeToken")
            .doesNotContain("approvalTokenDigest")
            .doesNotContain("argumentsDigest")
            .doesNotContain("replayEnvelope")
            .doesNotContain("rawArguments")
    }

    @Test
    fun `HTML does not contain resume button or action`() {
        val body = mockMvc.perform(get("/tramai/sovereign/reviewer"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        assertThat(body)
            .doesNotContain("actionResume")
            .doesNotContain("btn-resume")
            .doesNotContain("Resume")
    }

    @Test
    fun `JS payload uses correct approve slash deny field names`() {
        val body = mockMvc.perform(get("/tramai/sovereign/reviewer"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        assertThat(body)
            .contains("actorId")
            .contains("actorRole")
            .contains("expectedVersion")
            .contains("item?.version")
    }
}
