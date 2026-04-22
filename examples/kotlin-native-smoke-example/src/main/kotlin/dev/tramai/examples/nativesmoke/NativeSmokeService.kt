package dev.tramai.examples.nativesmoke

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation

@AiService
interface NativeSmokeService {
    @Operation(
        prompt = "Return a native smoke response",
        model = "native-smoke-model",
    )
    fun respond(input: String): String
}
