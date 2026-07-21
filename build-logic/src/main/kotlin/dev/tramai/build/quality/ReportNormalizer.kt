package dev.tramai.build.quality

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File

/**
 * Ensures deterministic output: LF endings, UTF-8, sorted keys, repo-relative paths.
 */
object ReportNormalizer {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun writeJson(value: Any, outputFile: File) {
        outputFile.parentFile.mkdirs()
        val json = mapper.writeValueAsString(value)
        // Ensure LF endings
        outputFile.writeText(json.replace("\r\n", "\n"), Charsets.UTF_8)
    }

    fun toJson(value: Any): String {
        return mapper.writeValueAsString(value).replace("\r\n", "\n")
    }

    fun <T> readJson(file: File, clazz: Class<T>): T {
        val content = file.readText(Charsets.UTF_8)
        return mapper.readValue(content, clazz)
    }

    /**
     * Normalize a file path to be repo-relative.
     */
    fun repoRelativePath(file: File, rootDir: File): String {
        return file.absolutePath.removePrefix(rootDir.absolutePath).removePrefix("/")
    }

    /**
     * Count lines in a file, categorizing by type.
     */
    fun countLines(file: File): LineCounts {
        if (!file.isFile || !file.extension.equals("kt", ignoreCase = true)) {
            return LineCounts(0, 0, 0, 0)
        }
        val lines = file.readLines(Charsets.UTF_8)
        var total = lines.size
        var nonBlank = 0
        var comments = 0
        var code = 0
        var inBlockComment = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            nonBlank++

            if (inBlockComment) {
                comments++
                if (trimmed.contains("*/")) inBlockComment = false
                continue
            }

            if (trimmed.startsWith("//")) {
                comments++
                continue
            }

            if (trimmed.startsWith("/*")) {
                comments++
                if (!trimmed.contains("*/")) inBlockComment = true
                continue
            }

            // Heuristic: lines starting with * inside block comments
            if (trimmed.startsWith("*") && !trimmed.startsWith("*/")) {
                comments++
                continue
            }

            code++
        }

        return LineCounts(total, nonBlank, comments, code)
    }

    data class LineCounts(
        val totalLines: Int,
        val nonBlankLines: Int,
        val commentLines: Int,
        val codeLines: Int
    )

    /**
     * Count non-blank lines in a file (simplified for non-Kotlin files).
     */
    fun countNonBlankLines(file: File): Int {
        if (!file.isFile) return 0
        return file.readLines(Charsets.UTF_8).count { it.isNotBlank() }
    }
}
