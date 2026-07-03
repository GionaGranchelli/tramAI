package dev.tramai.spring.provider.openai

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Binds `tramai.providers.*` YAML entries to typed properties.
 *
 * This v1 auto-configuration only consumes the `local-lab-provider`
 * entry. The map shape is intentionally broader so future provider
 * auto-configuration can support additional provider names.
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
 */
@ConfigurationProperties("tramai")
class TramaiProviderProperties {
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
