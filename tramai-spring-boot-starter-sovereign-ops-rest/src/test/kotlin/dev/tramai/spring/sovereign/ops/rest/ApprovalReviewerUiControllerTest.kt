package dev.tramai.spring.sovereign.ops.rest

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [ApprovalReviewerUiController::class])
@AutoConfigureMockMvc
class ApprovalReviewerUiControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `GET reviewer returns 200`() {
        mockMvc.perform(get("/tramai/sovereign/reviewer"))
            .andExpect(status().isOk)
    }

    @Test
    fun `HTML does not contain sensitive field names`() {
        val body = mockMvc.perform(get("/tramai/sovereign/reviewer"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        assert(body.contains("Preview"))
        assert(!body.contains("resumeToken"))
        assert(!body.contains("approvalTokenDigest"))
        assert(!body.contains("argumentsDigest"))
        assert(!body.contains("replayEnvelope"))
        assert(!body.contains("rawArguments"))
    }
}
