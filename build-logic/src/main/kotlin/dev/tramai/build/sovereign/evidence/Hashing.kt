package dev.tramai.build.sovereign.evidence

import java.io.File
import java.security.MessageDigest

/** Deterministic hashing helpers for release evidence (sha256 / tree hash / counts). */
object Hashing {

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Deterministic tree hash: path + 0-byte + file hash + 0-byte, files sorted by relative path. */
    fun treeHash(dir: File): String {
        val files = dir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files) {
            val relativePath = file.relativeTo(dir).invariantSeparatorsPath
            val fileHash = sha256Hex(file)
            digest.update(relativePath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(fileHash.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun fileCount(dir: File): Int = dir.walkTopDown().count { it.isFile }

    /** Minimal JSON string escaping used by generated evidence artifacts. */
    fun jsonEscape(value: String): String {
        val sb = StringBuilder()
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }
}
