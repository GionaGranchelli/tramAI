package io.aurora.core.exception

/**
 * Thrown by tool authors to indicate input validation failures.
 */
class ToolInvalidInputException(message: String) : AuroraException(message)
