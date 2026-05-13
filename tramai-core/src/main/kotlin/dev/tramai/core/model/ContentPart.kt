package dev.tramai.core.model

/**
 * Multi-part content item used in messages that carry non-text payloads
 * such as images alongside text.
 */
sealed interface ContentPart {
    /**
     * Plain-text content fragment.
     */
    data class TextPart(val text: String) : ContentPart

    /**
     * Image payload embedded directly in the message.
     *
     * @property mimeType the media type of the image, e.g. "image/png", "image/jpeg"
     * @property data the raw image bytes. **Important:** The byte array is stored
     *   by reference and is not defensively copied. Callers must not mutate the
     *   array after constructing this instance.
     */
    class ImagePart(
        mimeType: String,
        data: ByteArray,
    ) : ContentPart {
        val mimeType: String = mimeType
        val data: ByteArray = data.copyOf()

        init {
            require(this.data.isNotEmpty()) { "Image data must not be empty" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImagePart) return false
            return mimeType == other.mimeType && data.contentEquals(other.data)
        }

        override fun hashCode(): Int = 31 * mimeType.hashCode() + data.contentHashCode()

        override fun toString(): String =
            "ImagePart(mimeType='$mimeType', data=${data.size} bytes)"
    }
}
