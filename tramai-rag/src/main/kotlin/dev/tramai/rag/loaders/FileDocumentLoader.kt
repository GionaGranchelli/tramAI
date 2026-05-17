package dev.tramai.rag.loaders

import dev.tramai.rag.Document
import dev.tramai.rag.DocumentLoader
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * A [DocumentLoader] that reads text content from local file paths.
 *
 * NOTE: This loader does not perform path traversal protection. The caller
 * is responsible for ensuring that the provided path is safe and within
 * expected bounds (e.g., by resolving against a known base directory and
 * checking the canonical path).
 *
 * @param charset  The charset to use when reading the file (default: UTF-8).
 * @param maxBytes Maximum number of bytes to read; throws [IllegalArgumentException]
 *                 if the file exceeds this limit (default: 10 MB).
 * @throws IllegalArgumentException if the path does not exist, is not a regular file,
 *         or exceeds [maxBytes].
 * @throws RuntimeException if the file cannot be read.
 */
class FileDocumentLoader(
    private val charset: Charset = StandardCharsets.UTF_8,
    private val maxBytes: Long = 10_000_000,
    private val ioDispatcher: CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
) : DocumentLoader {

    override suspend fun load(source: String): Document {
        require(source.isNotBlank()) { "FileDocumentLoader: source must not be blank" }

        val path = Path.of(source)
        require(Files.exists(path)) { "FileDocumentLoader: file does not exist: $source" }
        require(Files.isRegularFile(path)) { "FileDocumentLoader: path is not a regular file: $source" }
        require(Files.size(path) <= maxBytes) {
            "FileDocumentLoader: file $source is ${Files.size(path)} bytes, exceeds maxBytes limit of $maxBytes"
        }

        val content = try {
            withContext(ioDispatcher) {
                Files.readString(path, charset)
            }
        } catch (e: IOException) {
            throw RuntimeException("FileDocumentLoader: failed to read file: $source", e)
        }

        return Document(
            source = source,
            content = content,
            metadata = mapOf("source_type" to "file", "file_path" to path.toAbsolutePath().toString()),
        )
    }
}
