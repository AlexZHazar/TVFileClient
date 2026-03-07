package com.example.tvfileclient

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import okio.buffer
import java.io.InputStream

class InputStreamRequestBody(
    private val inputStream: InputStream,
    private val contentLength: Long,
    private val mediaType: MediaType?,
    private val progressListener: ((Long, Long) -> Unit)? = null
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: BufferedSink) {
        var bytesWritten = 0L
        val bufferSize = 8192 // 8KB буфер
        val source = inputStream.source().buffer()

        try {
            while (true) {
                val read = source.read(sink.buffer, bufferSize.toLong())
                if (read == -1L) break

                bytesWritten += read
                sink.emitCompleteSegments()

                // Сообщаем о прогрессе
                progressListener?.invoke(bytesWritten, contentLength)
            }
        } finally {
            source.close()
            inputStream.close()
        }
    }
}