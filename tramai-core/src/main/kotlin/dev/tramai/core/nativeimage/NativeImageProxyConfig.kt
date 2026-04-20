package dev.tramai.core.nativeimage

import dev.tramai.core.annotations.AiService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * Generates GraalVM proxy metadata for Tramai `@AiService` interfaces.
 */
object NativeImageProxyConfig {
    @JvmStatic
    fun json(vararg serviceTypes: KClass<*>): String {
        val normalized = serviceTypes.toList().also(::validateServiceTypes)
        return normalized.joinToString(
            prefix = "[\n",
            postfix = "\n]",
            separator = ",\n",
        ) { serviceType ->
            """  { "interfaces": [ "${serviceType.java.name}" ] }"""
        }
    }

    @JvmStatic
    fun write(
        outputPath: Path,
        vararg serviceTypes: KClass<*>,
    ) {
        val json = json(*serviceTypes)
        outputPath.parent?.let(Files::createDirectories)
        Files.writeString(outputPath, json)
    }

    private fun validateServiceTypes(serviceTypes: List<KClass<*>>) {
        require(serviceTypes.isNotEmpty()) {
            "At least one @AiService interface must be supplied for native-image proxy metadata generation"
        }
        serviceTypes.forEach { serviceType ->
            val javaType = serviceType.java
            require(serviceType.java.isInterface) {
                "${javaType.name} must be an interface to generate JDK proxy metadata"
            }
            require(javaType.isAnnotationPresent(AiService::class.java)) {
                "${javaType.name} must be annotated with @AiService to generate Tramai proxy metadata"
            }
        }
    }
}
