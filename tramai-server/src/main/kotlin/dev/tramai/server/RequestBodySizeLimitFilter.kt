package dev.tramai.server

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import org.springframework.web.filter.OncePerRequestFilter

class RequestBodySizeLimitFilter(
    private val maxRequestBodyBytes: Long,
) : OncePerRequestFilter() {
    init {
        require(maxRequestBodyBytes > 0) { "maxRequestBodyBytes must be greater than zero" }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method !in setOf("POST", "PUT", "PATCH") || !request.requestURI.startsWith("/workflows/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: jakarta.servlet.http.HttpServletResponse,
        filterChain: FilterChain,
    ) {
        filterChain.doFilter(LimitedBodyRequest(request, maxRequestBodyBytes), response)
    }
}

class RequestBodyTooLargeException(
    message: String,
) : RuntimeException(message)

private class LimitedBodyRequest(
    request: HttpServletRequest,
    private val maxRequestBodyBytes: Long,
) : HttpServletRequestWrapper(request) {
    override fun getInputStream(): ServletInputStream {
        if (contentLengthLong > maxRequestBodyBytes) {
            throw RequestBodyTooLargeException("Request body is too large")
        }
        return LimitedServletInputStream(super.getInputStream(), maxRequestBodyBytes)
    }
}

private class LimitedServletInputStream(
    private val delegate: ServletInputStream,
    private val maxRequestBodyBytes: Long,
) : ServletInputStream() {
    private var bytesRead = 0L

    override fun read(): Int {
        val read = delegate.read()
        if (read != -1) {
            recordBytes(1)
        }
        return read
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val read = delegate.read(buffer, offset, length)
        if (read > 0) {
            recordBytes(read.toLong())
        }
        return read
    }

    override fun isFinished(): Boolean = delegate.isFinished

    override fun isReady(): Boolean = delegate.isReady

    override fun setReadListener(readListener: ReadListener?) {
        delegate.setReadListener(readListener)
    }

    private fun recordBytes(count: Long) {
        bytesRead += count
        if (bytesRead > maxRequestBodyBytes) {
            throw RequestBodyTooLargeException("Request body is too large")
        }
    }
}
