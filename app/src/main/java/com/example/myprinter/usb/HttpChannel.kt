package com.example.myprinter.usb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    /** [name] in lower case. */
    fun header(name: String): String? = headers[name]
    val text: String get() = String(body, Charsets.UTF_8)
}

/**
 * HTTP/1.1 over a byte pipe, as scanners speak it on a USB interface (HP LEDM, IPP-over-USB):
 * one request, then its response, on the same pair of bulk endpoints.
 * Callers format the whole request, so each protocol can send exactly what its devices expect.
 */
class HttpChannel(private val input: InputStream, private val output: OutputStream) {
    fun exchange(request: ByteArray): HttpResponse {
        output.write(request)
        output.flush()
        return readResponse()
    }

    private fun readResponse(): HttpResponse {
        // Like HPLIP, skip anything left over from a previous response until a status line.
        var statusLine: String
        do {
            statusLine = readLine()
        } while (!statusLine.startsWith("HTTP/1."))
        val status = statusLine.substringAfter(' ').take(3).toIntOrNull()
            ?: throw IOException("Bad HTTP status line: $statusLine")

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked()
            headers["content-length"] != null -> readExactly(headers.getValue("content-length").toInt())
            // No framing: nothing can mark the end on a USB pipe, so there is no body.
            else -> ByteArray(0)
        }
        return HttpResponse(status, headers, body)
    }

    private fun readChunked(): ByteArray {
        val body = ByteArrayOutputStream()
        while (true) {
            val size = readLine().substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("Bad chunk size")
            if (size == 0) break
            body.write(readExactly(size))
            readLine() // CRLF after the chunk data
        }
        // Trailer headers, up to the blank line.
        while (readLine().isNotEmpty()) Unit
        return body.toByteArray()
    }

    private fun readExactly(size: Int): ByteArray {
        val data = ByteArray(size)
        var done = 0
        while (done < size) {
            val n = input.read(data, done, size - done)
            if (n < 0) throw IOException("Connection closed after $done of $size bytes")
            done += n
        }
        return data
    }

    /** A line without its CRLF (or bare LF). */
    private fun readLine(): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("Connection closed")
            if (b == '\n'.code) break
            line.write(b)
        }
        return line.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
    }
}
