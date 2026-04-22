package dev.tramai.examples.nativesmoke

import dev.tramai.core.nativeimage.NativeImageProxyConfig
import java.nio.file.Path

fun main() {
    NativeImageProxyConfig.write(
        outputPath = Path.of(
            "src/main/resources/META-INF/native-image/dev.tramai.examples/kotlin-native-smoke-example/proxy-config.json",
        ),
        NativeSmokeService::class,
    )
}
