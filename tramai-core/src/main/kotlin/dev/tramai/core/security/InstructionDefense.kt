package dev.tramai.core.security

fun interface InstructionDefense {
    fun wrap(prompt: String, systemInstructions: String): String
}
