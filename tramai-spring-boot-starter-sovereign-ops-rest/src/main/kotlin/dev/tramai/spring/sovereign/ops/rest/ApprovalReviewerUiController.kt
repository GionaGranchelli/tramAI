package dev.tramai.spring.sovereign.ops.rest

import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("\${tramai.sovereign.ops.rest.base-path:/tramai/sovereign}")
class ApprovalReviewerUiController {

    private val pageHtml: String

    init {
        val resource = ClassPathResource("dev/tramai/spring/sovereign/ops/rest/reviewer-ui.html")
        pageHtml = resource.inputStream.bufferedReader().use { it.readText() }
    }

    @GetMapping("/reviewer", produces = [MediaType.TEXT_HTML_VALUE])
    fun reviewerPage(): ResponseEntity<String> =
        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .body(pageHtml)
}
