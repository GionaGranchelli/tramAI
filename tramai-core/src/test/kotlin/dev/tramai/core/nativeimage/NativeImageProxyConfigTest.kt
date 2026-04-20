package dev.tramai.core.nativeimage

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

class NativeImageProxyConfigTest {
    @Test
    fun `generates proxy config json for ai service interfaces`() {
        val json = NativeImageProxyConfig.json(TestNativeService::class)

        assertThat(json)
            .contains(TestNativeService::class.java.name)
            .contains("\"interfaces\"")
    }

    @Test
    fun `writes proxy config json to disk`() {
        val directory = createTempDirectory("tramai-native-image")
        val outputPath = directory.resolve("proxy-config.json")

        NativeImageProxyConfig.write(outputPath, TestNativeService::class)

        assertThat(Files.readString(outputPath)).contains(TestNativeService::class.java.name)
    }

    @Test
    fun `rejects non ai service types`() {
        assertThatThrownBy { NativeImageProxyConfig.json(NotAnnotatedNativeService::class) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("@AiService")
    }
}

@AiService
private interface TestNativeService {
    @Operation(
        prompt = "Return a native-image test response",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun respond(name: String): String
}

private interface NotAnnotatedNativeService {
    suspend fun respond(name: String): String
}
