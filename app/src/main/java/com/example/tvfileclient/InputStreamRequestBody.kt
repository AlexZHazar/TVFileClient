package com.example.tvfileclient

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream

class InputStreamRequestBody(
    private val inputStream: InputStream,
    private val contentLength: Long,
    private val mediaType: MediaType?
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: BufferedSink) {
        // Читаем из inputStream и пишем в sink (буфер OkHttp) напрямую, без загрузки в память
        inputStream.source().use { source ->
            sink.writeAll(source)
        }
    }
}