package dev.tramai.core.model

internal fun validateField(fieldName: String, value: String) {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
    require(value == value.trim()) { "$fieldName must not have surrounding whitespace" }
    require(value.length <= 256) { "$fieldName must be at most 256 characters" }
    require(value.none(Char::isISOControl)) { "$fieldName must not contain control characters" }
}
