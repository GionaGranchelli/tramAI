package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.lang.reflect.Proxy
import kotlin.test.Test

/**
 * Smoke test for GraalVM Native Image compatibility.
 * 
 * Verifies that the core engine and its proxy generation logic do not rely on
 * prohibited reflection or dynamic features without appropriate metadata.
 */
class NativeImageSmokeTest {

    @Test
    fun `engine can create proxy for pre-registered native-image interface`() {
        // This test simulates a native-image environment where JDK proxies must be pre-registered.
        // We use the real TramaiEngine to create a proxy and verify it works.
        
        val provider = object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                return ModelResponse(content = "native-image response")
            }
            override fun providerId(): String = "native-provider"
        }
        
        val engine = TramaiEngine(provider)
        val service = engine.create(NativeSmokeService::class)
        
        val result = runBlocking { service.call("test") }
        
        assertThat(result).isEqualTo("native-image response")
        assertThat(Proxy.isProxyClass(service::class.java)).isTrue()
    }

    @AiService
    interface NativeSmokeService {
        @Operation(
            prompt = "Smoke test",
            model = "smoke-model"
        )
        suspend fun call(input: String): String
    }
}
