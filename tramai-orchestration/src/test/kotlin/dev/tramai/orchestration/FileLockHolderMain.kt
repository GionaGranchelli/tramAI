package dev.tramai.orchestration

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.absolute

/**
 * Deterministic helper JVM for cross-process file-lock tests.
 *
 * Usage: `java -cp <test-classpath> dev.tramai.orchestration.FileLockHolderMain
 * <lockPath> <markerPath> <releaseFile>`
 *
 * 1. Opens [lockPath] with [FileChannel] and acquires the OS lock.
 * 2. Writes [markerPath] with the text "locked" (readiness marker).
 * 3. Holds the lock until [releaseFile] exists, then exits cleanly.
 *
 * The test spawns this via `ProcessBuilder` so the lock is acquired by a REAL other
 * JVM process — the same locking mechanism production uses — not the Unix `flock`
 * command. On abnormal termination (SIGKILL, test failure) the OS releases the lock
 * automatically; the test also uses a `finally` to force-destroy the helper.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: FileLockHolderMain <lockPath> <markerPath> <releaseFile>" }
    val lockPath = Path.of(args[0]).absolute()
    val markerPath = Path.of(args[1]).absolute()
    val releaseFile = Path.of(args[2]).absolute()

    Files.createDirectories(lockPath.parent)
    FileChannel.open(
        lockPath,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
    ).use { channel ->
        channel.lock().use {
            Files.writeString(markerPath, "locked")
            while (!Files.exists(releaseFile)) {
                Thread.sleep(100)
            }
        }
    }
}
