package dev.tramai.dashboard

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Serves a small JavaScript snippet that bootstraps the dashboard SPA with
 * runtime configuration (API base URL, feature flags, and auth settings).
 *
 * The SPA loads this via a `<script src="/tramai-settings.js">` tag in its
 * `index.html` before the Vue app mounts.
 */
@RestController
class DashboardSettingsController {

    @GetMapping("/tramai-settings.js", produces = ["text/javascript"])
    fun settings(request: HttpServletRequest): String {
        val contextPath = request.contextPath ?: ""
        val apiBaseUrl = "$contextPath"

        return """
            |window.__TRAMAI__ = {
            |    apiBaseUrl: "$apiBaseUrl",
            |    features: { auditLog: true, workerManagement: true, scheduleManagement: true },
            |    auth: { required: false, provider: "none" }
            |};
        """.trimMargin()
    }
}
