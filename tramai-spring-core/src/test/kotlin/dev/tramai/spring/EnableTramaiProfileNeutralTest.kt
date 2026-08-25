package dev.tramai.spring

import dev.tramai.spring.enablefixture.StandardEnableTramaiFixture
import dev.tramai.spring.enablefixture.StandardEnableTramaiService
import dev.tramai.standalone.Tramai
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.test.context.support.TestPropertySourceUtils

class EnableTramaiProfileNeutralTest {

    @Test
    fun `annotation driven context defaults to standard and discovers AiService`() {
        AnnotationConfigApplicationContext().use { context ->
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                context,
                "tramai.models.local-model=local-provider",
                "tramai.default-provider=local-provider",
            )
            context.register(StandardEnableTramaiFixture::class.java)
            context.refresh()

            assertNotNull(context.getBean(Tramai::class.java))
            val service = context.getBean(StandardEnableTramaiService::class.java)
            assertEquals("STANDARD_ENABLE_OK", runBlocking { service.analyze("invoice") })
        }
    }
}
