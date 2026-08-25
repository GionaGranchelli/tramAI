package dev.tramai.spring

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.springframework.beans.factory.support.DefaultListableBeanFactory

class AiServiceFactoryBeanTest {

    @Test
    fun `factory delegates service creation to active runtime creator`() {
        val beanFactory = DefaultListableBeanFactory()
        var requestedType: KClass<*>? = null
        val expected = TestAiService()
        val creator: AiServiceCreator = { serviceType ->
            requestedType = serviceType
            expected
        }
        beanFactory.registerSingleton(AI_SERVICE_CREATOR_BEAN_NAME, creator)

        val factory = AiServiceFactoryBean(TestAiService::class.java)
        factory.setBeanFactory(beanFactory)

        assertSame(expected, factory.getObject())
        assertEquals(TestAiService::class, requestedType)
    }

    @Test
    fun `factory fails loudly when runtime creator bean has wrong type`() {
        val beanFactory = DefaultListableBeanFactory()
        beanFactory.registerSingleton(AI_SERVICE_CREATOR_BEAN_NAME, "not-a-function")

        val factory = AiServiceFactoryBean(TestAiService::class.java)
        factory.setBeanFactory(beanFactory)

        val failure = assertFailsWith<IllegalStateException> {
            factory.getObject()
        }
        assertEquals("AI service creator bean has invalid type", failure.message)
    }

    private class TestAiService
}
