package dev.tramai.dashboard

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@AutoConfiguration
@ConditionalOnClass(DashboardMarker::class)
@ConditionalOnProperty(prefix = "tramai.dashboard", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class DashboardAutoConfiguration : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/dashboard/**")
            .addResourceLocations("classpath:/META-INF/tramai-dashboard/")
    }

    @Bean
    fun dashboardSettingsController(
        applicationContext: ApplicationContext,
        objectMapper: ObjectMapper,
    ): DashboardSettingsController = DashboardSettingsController(applicationContext, objectMapper)
}
