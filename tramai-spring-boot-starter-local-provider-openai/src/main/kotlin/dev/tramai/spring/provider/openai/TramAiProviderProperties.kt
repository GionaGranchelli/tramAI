package dev.tramai.spring.provider.openai

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Maps `tramai.providers.*` YAML entries to typed properties.
 *
 * YAML shape:
 * ```
 * tramai:
 *   providers:
 *     local-lab-provider:
 *       type: openai
 *       base-url: http://localhost:11434/v1
 *       api-key: local-dev
 *       model: qwen2.5:7b
 * ```
 *
 * Each entry key is a dynamic provider name; the value is a [ProviderEntry].
 * Only entries with `type=openai` (case-insensitive) are auto-configured.
 */
@ConfigurationProperties("tramai")
class TramAiProviderProperties {
    var providers: Map<String, ProviderEntry> = emptyMap()
    /**
     * A single provider entry under `tramai.providers.<name>`.
     */
    class ProviderEntry {
        var type: String? = null
        var baseUrl: String? = null
        var apiKey: String? = null
        var model: String? = null
    }
}
