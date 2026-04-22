package dev.tramai.examples.nativesmoke

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.standalone.Tramai

fun main() {
    val tramai = Tramai {
        provider(
            provider = object : ModelProvider {
                override suspend fun complete(request: ModelRequest): ModelResponse {
                    return ModelResponse(content = "native-smoke-ok:${request.model}")
                }

                override fun providerId(): String = "native-smoke"
            },
            name = "native-smoke",
            default = true,
        )
        model("native-smoke-model", "native-smoke")
    }

    val service = tramai.create(NativeSmokeService::class)
    val result = service.respond("hello")
    check(result == "native-smoke-ok:native-smoke-model") {
        "Unexpected native smoke result: $result"
    }
    println(result)
}
