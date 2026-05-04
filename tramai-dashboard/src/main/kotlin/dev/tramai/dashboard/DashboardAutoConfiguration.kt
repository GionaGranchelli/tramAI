package dev.tramai.dashboard

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@AutoConfiguration
@ConditionalOnClass(DashboardMarker::class)
class DashboardAutoConfiguration : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/dashboard/**")
            .addResourceLocations("classpath:/META-INF/tramai-dashboard/")
    }

    @Bean
    fun dashboardSettingsController(): DashboardSettingsController = DashboardSettingsController()

    @Controller
    class DashboardRedirectController {

        @GetMapping("/")
        fun index(): String = "redirect:/dashboard/index.html"
    }
}
