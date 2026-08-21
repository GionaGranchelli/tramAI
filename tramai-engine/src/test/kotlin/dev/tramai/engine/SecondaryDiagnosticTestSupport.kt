package dev.tramai.engine

import java.text.MessageFormat
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Epic 5.3 test support — captures the safe secondary-failure diagnostic sink
 * (`dev.tramai.core.observation.secondary`) during [block] and returns the
 * formatted log messages. The handler is removed in a finally block so a
 * failing test can never leak a capture handler into other tests.
 */
fun withCapturedSecondaryDiagnostics(block: () -> Unit): List<String> {
    val logger = Logger.getLogger("dev.tramai.core.observation.secondary")
    val records = mutableListOf<LogRecord>()
    val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records += record
        }

        override fun flush() = Unit

        override fun close() = Unit
    }
    logger.addHandler(handler)
    try {
        block()
    } finally {
        logger.removeHandler(handler)
    }
    return records.map { record ->
        val params = record.parameters
        if (params == null) record.message else MessageFormat.format(record.message, *params)
    }
}
