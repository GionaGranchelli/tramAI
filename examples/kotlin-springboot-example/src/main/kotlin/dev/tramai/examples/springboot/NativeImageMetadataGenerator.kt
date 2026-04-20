package dev.tramai.examples.springboot

import dev.tramai.core.nativeimage.NativeImageProxyConfig
import java.nio.file.Path

/**
 * Small helper used by the example build to refresh GraalVM proxy metadata.
 */
object NativeImageMetadataGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        NativeImageProxyConfig.write(
            outputPath = Path.of(
                "src/main/resources/META-INF/native-image/dev.tramai.examples/kotlin-springboot-example/proxy-config.json",
            ),
            InvoiceAnalyzer::class,
        )
    }
}
