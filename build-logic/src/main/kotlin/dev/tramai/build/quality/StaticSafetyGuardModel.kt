package dev.tramai.build.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File

data class StaticSafetyGuardConfig(
    val schemaVersion: Int,
    val rules: List<StaticSafetyRule>,
    val exemptions: List<StaticSafetyExemption>,
)

data class StaticSafetyRule(
    val id: String,
    val match: String,
    val symbols: List<String> = emptyList(),
    val receivers: List<String> = emptyList(),
    val sensitiveSymbols: List<String> = emptyList(),
    val blockReadSymbols: List<String> = emptyList(),
    val approvedPaths: List<String> = emptyList(),
    val receiverOrCall: List<String> = emptyList(),
)

data class StaticSafetyExemption(
    val rule: String,
    val path: String,
    val symbol: String,
    val rationale: String,
)

object StaticSafetyGuardConfigParser {
    private val mapper = ObjectMapper(YAMLFactory())
    private val kinds = setOf("call-name", "receiver-call", "body-use-block", "multi")

    fun parse(
        text: String,
        repositoryRoot: File,
    ): StaticSafetyGuardConfig {
        val root =
            try {
                mapper.readTree(text)
            } catch (
                e: Exception,
            ) {
                throw IllegalArgumentException("static safety config YAML is malformed: ${e.message}")
            }
        require(root != null && root.isObject) { "static safety config root must be an object" }
        val schema = root.get("schemaVersion")
        require(schema != null && schema.isIntegralNumber && schema.asInt() == 1) {
            "static safety config schemaVersion must be the integral number 1"
        }
        val rulesNode = root.get("rules")
        require(rulesNode != null && rulesNode.isArray) { "static safety config rules must be an array" }
        val rules = rulesNode.map { parseRule(it, repositoryRoot) }
        val ids = rules.map { it.id }.toSet()
        val exNode = root.get("exemptions")
        require(exNode != null && exNode.isArray) { "static safety config exemptions must be an array" }
        val seen = mutableSetOf<String>()
        val exemptions =
            exNode.map { node ->
                require(node.isObject) { "exemption must be an object" }
                val rule = text(node, "rule", false)
                val path = text(node, "path", false)
                val symbol = text(node, "symbol", false)
                val rationale = text(node, "rationale", true)
                require(ids.contains(rule)) { "exemption references unknown rule '$rule'" }
                require(!File(path).isAbsolute && path.split('/').none { it == ".." }) { "exemption path escapes repository root: $path" }
                val file = File(repositoryRoot, path)
                require(file.exists()) { "exemption path does not exist: $path" }
                require(path.replace('\\', '/').matches(Regex(".*/src/main/.*"))) { "exemption path is outside production roots: $path" }
                require(seen.add("$rule\u0000$path\u0000$symbol")) { "duplicate exemption: $rule | $path | $symbol" }
                StaticSafetyExemption(rule, path, symbol, rationale)
            }
        return StaticSafetyGuardConfig(1, rules, exemptions)
    }

    private fun parseRule(
        node: JsonNode,
        root: File,
    ): StaticSafetyRule {
        require(node.isObject) { "rule must be an object" }
        val id = text(node, "id", false)
        val match = text(node, "match", false)
        require(kinds.contains(match)) { "unknown static safety match kind '$match'" }

        fun texts(name: String): List<String> {
            val n = node.get(name) ?: return emptyList()
            require(n.isArray) { "rule $id field $name must be an array" }
            return n.map {
                require(it.isTextual) { "rule $id field $name entries must be text scalars" }
                val v = it.textValue().trim()
                require(v.isNotEmpty()) { "rule $id field $name contains a blank symbol" }
                v
            }
        }
        val symbols = texts("symbols")
        val receivers = texts("receivers")
        val sensitive = texts("sensitiveSymbols")
        val blocks = texts("blockReadSymbols")
        val approved = texts("approvedPaths")
        val roc = texts("receiverOrCall")
        require(
            when (match) {
                "call-name" -> {
                    symbols.isNotEmpty()
                }

                "receiver-call" -> {
                    symbols.isNotEmpty() && receivers.isNotEmpty() &&
                        sensitive.isNotEmpty()
                }

                "body-use-block" -> {
                    symbols.isNotEmpty() && blocks.isNotEmpty()
                }

                "multi" -> {
                    symbols.isNotEmpty() &&
                        blocks.isNotEmpty()
                }

                else -> {
                    false
                }
            },
        ) { "rule $id is missing required fields for match $match" }
        approved.forEach { p ->
            require(!File(p).isAbsolute && p.split('/').none { it == ".." }) { "approved path escapes repository root: $p" }
            require(File(root, p).isDirectory) { "approvedPaths entry is not a directory: $p" }
        }
        return StaticSafetyRule(id, match, symbols, receivers, sensitive, blocks, approved, roc)
    }

    private fun text(
        node: JsonNode,
        name: String,
        allowBlank: Boolean,
    ): String {
        val n = node.get(name)
        require(n != null && n.isTextual) { "$name must be a text scalar" }
        val value = n.textValue().trim()
        require(allowBlank || value.isNotEmpty()) { "$name must not be blank" }
        require(value.isNotEmpty()) { "$name must not be blank" }
        return value
    }
}
