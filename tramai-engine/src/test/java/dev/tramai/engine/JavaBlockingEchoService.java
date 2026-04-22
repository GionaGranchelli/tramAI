package dev.tramai.engine;

import dev.tramai.core.annotations.AiService;
import dev.tramai.core.annotations.Operation;

@AiService
public interface JavaBlockingEchoService {
    @Operation(
        prompt = "Echo the payload",
        model = "claude-sonnet-4-20250514"
    )
    String echo(String payload);
}
