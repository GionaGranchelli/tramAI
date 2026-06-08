package dev.tramai.core.approval

fun interface ToolArgumentsDigester {
    fun digest(arguments: SensitiveToolArguments): Sha256Digest
}
