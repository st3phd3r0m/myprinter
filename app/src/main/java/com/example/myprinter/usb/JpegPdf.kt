package com.example.myprinter.usb

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/** A scanned page: a JPEG file and the resolution it was scanned at, which gives its paper size. */
data class ScannedPage(val file: File, val dpi: Int)

class JpegInfo(val width: Int, val height: Int, val components: Int)

object Jpeg {
    /** @throws IOException if [jpeg] has no frame header. */
    fun info(jpeg: ByteArray): JpegInfo {
        val sof = frameHeader(jpeg) ?: throw IOException("Not a JPEG image")
        return JpegInfo(width = u16(jpeg, sof + 7), height = u16(jpeg, sof + 5), components = jpeg[sof + 9].toInt() and 0xFF)
    }

    /**
     * A scanner can stream a page before knowing its length: the frame header then says height 0
     * and the real one comes in a DNL marker after the image data. Android's decoder needs it in
     * the header, so it is copied there. Other images are returned as they are.
     */
    fun withKnownHeight(jpeg: ByteArray): ByteArray {
        val sof = frameHeader(jpeg) ?: return jpeg
        if (u16(jpeg, sof + 5) != 0) return jpeg
        // DNL: FF DC, length 4, number of lines. Searched from the end, past the image data.
        for (i in jpeg.size - 6 downTo sof) {
            if (jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xDC.toByte() && u16(jpeg, i + 2) == 4) {
                return jpeg.copyOf().also {
                    it[sof + 5] = jpeg[i + 4]
                    it[sof + 6] = jpeg[i + 5]
                }
            }
        }
        return jpeg
    }

    /** Offset of the SOFn marker, or null. */
    private fun frameHeader(jpeg: ByteArray): Int? {
        if (jpeg.size < 4 || u16(jpeg, 0) != 0xFFD8) return null
        var i = 2
        while (i + 4 <= jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) return null
            val marker = jpeg[i + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> { i++; continue } // fill byte
                marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC ->
                    return if (i + 10 <= jpeg.size) i else null
                marker == 0xDA || marker == 0xD9 -> return null // image data before any frame header
                else -> i += 2 + u16(jpeg, i + 2)
            }
        }
        return null
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
}

/**
 * Writes scanned pages as a PDF, one page per JPEG, embedded as is (DCTDecode): no decoding, so
 * no memory spent on full-size bitmaps, and no quality lost.
 */
object JpegPdf {
    fun write(out: OutputStream, pages: List<ScannedPage>) {
        val pdf = CountingStream(out)
        val offsets = mutableListOf<Long>()
        fun obj(content: String) {
            offsets += pdf.count
            pdf.text("${offsets.size} 0 obj\n$content\nendobj\n")
        }

        pdf.text("%PDF-1.4\n")
        pdf.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        // Objects 1 and 2, then three per page (page, content, image) from object 3.
        obj("<</Type/Catalog/Pages 2 0 R>>")
        val kids = pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
        obj("<</Type/Pages/Kids[$kids]/Count ${pages.size}>>")

        for ((index, page) in pages.withIndex()) {
            val jpeg = page.file.readBytes()
            val info = Jpeg.info(jpeg)
            val width = points(info.width, page.dpi)
            val height = points(info.height, page.dpi)
            val first = 3 + index * 3
            obj("<</Type/Page/Parent 2 0 R/MediaBox[0 0 $width $height]/Resources<</XObject<</Im0 ${first + 2} 0 R>>>>/Contents ${first + 1} 0 R>>")
            val content = "q $width 0 0 $height 0 0 cm /Im0 Do Q"
            obj("<</Length ${content.length}>>\nstream\n$content\nendstream")

            offsets += pdf.count
            val colorSpace = when (info.components) {
                1 -> "DeviceGray"
                4 -> "DeviceCMYK"
                else -> "DeviceRGB"
            }
            pdf.text("${offsets.size} 0 obj\n<</Type/XObject/Subtype/Image/Width ${info.width}/Height ${info.height}" +
                "/ColorSpace/$colorSpace/BitsPerComponent 8/Filter/DCTDecode/Length ${jpeg.size}>>\nstream\n")
            pdf.write(jpeg)
            pdf.text("\nendstream\nendobj\n")
        }

        val xref = pdf.count
        pdf.text("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { pdf.text(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        pdf.text("trailer\n<</Size ${offsets.size + 1}/Root 1 0 R>>\nstartxref\n$xref\n%%EOF\n")
        pdf.flush()
    }

    /** Pixels at [dpi] to PDF points (1/72 inch). */
    private fun points(pixels: Int, dpi: Int) = String.format(Locale.ROOT, "%.2f", pixels * 72.0 / dpi)

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        fun text(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()
    }
}
