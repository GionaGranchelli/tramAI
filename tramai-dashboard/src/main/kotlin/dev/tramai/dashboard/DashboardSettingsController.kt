package dev.tramai.dashboard

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
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
class DashboardSettingsController(
    private val applicationContext: ApplicationContext,
    private val objectMapper: ObjectMapper,
) {

    @GetMapping("/tramai-settings.js", produces = ["text/javascript"])
    fun settings(request: HttpServletRequest): String {
        val contextPath = request.contextPath ?: ""
        val apiBaseUrl = "$contextPath"
        val authRequired = applicationContext.environment
            .getProperty("tramai.dashboard.auth.required", Boolean::class.java, false)

        val settings = DashboardRuntimeSettings(
            apiBaseUrl = apiBaseUrl,
            features = DashboardFeatures(
                auditLog = true,
                workerManagement = true,
                scheduleManagement = true,
            ),
            auth = DashboardAuthSettings(
                required = authRequired,
                provider = resolveAuthProvider(
                    environment = applicationContext.environment,
                    authRequired = authRequired,
                ),
            ),
        )

        return "window.__TRAMAI__ = ${objectMapper.writeValueAsString(settings)};"
    }

    private fun resolveAuthProvider(
        environment: Environment,
        authRequired: Boolean,
    ): String {
        if (!authRequired) {
            return "none"
        }

        return when {
            applicationContext.getBeanNamesForTypeIfPresent("dev.tramai.platform.ApiKeyAuthenticator").isNotEmpty() -> "apikey"
            applicationContext.getBeanNamesForTypeIfPresent("org.springframework.security.oauth2.jwt.JwtDecoder").isNotEmpty() -> "oauth"
            applicationContext.getBeanNamesForTypeIfPresent("org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector").isNotEmpty() -> "oauth"
            applicationContext.getBeanNamesForTypeIfPresent("org.springframework.security.authentication.AuthenticationProvider").isNotEmpty() -> "custom"
            applicationContext.getBeanNamesForTypeIfPresent("org.springframework.security.web.SecurityFilterChain").isNotEmpty() -> "spring-security"
            else -> environment.getProperty("tramai.dashboard.auth.provider", "custom")
        }
    }
}

private data class DashboardRuntimeSettings(
    val apiBaseUrl: String,
    val features: DashboardFeatures,
    val auth: DashboardAuthSettings,
)

private data class DashboardFeatures(
    val auditLog: Boolean,
    val workerManagement: Boolean,
    val scheduleManagement: Boolean,
)

private data class DashboardAuthSettings(
    val required: Boolean,
    val provider: String,
)

private fun ApplicationContext.getBeanNamesForTypeIfPresent(className: String): Array<String> {
    val type = runCatching { Class.forName(className) }.getOrNull() ?: return emptyArray()
    return getBeanNamesForType(type)
}
