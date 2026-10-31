package com.example.myprinter.usb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.Closeable
import java.io.IOException

/** A document opened from a content Uri, rendered one page at a time for printing. */
sealed class PrintDocument : Closeable {
    abstract val pageCount: Int

    /** The returned bitmap stays valid until the next call or [close]. */
    abstract fun renderPage(index: Int): Bitmap

    private class Image(private val bitmap: Bitmap) : PrintDocument() {
        override val pageCount = 1
        override fun renderPage(index: Int) = bitmap
        override fun close() = bitmap.recycle()
    }

    private class Pdf(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) : PrintDocument() {
        private var current: Bitmap? = null

        override val pageCount = renderer.pageCount

        override fun renderPage(index: Int): Bitmap {
            // Free the previous page first: a 300 dpi A4 page is ~35 MB.
            current?.recycle()
            current = null
            return renderer.openPage(index).use { page ->
                // PDF page sizes are in points (1/72 inch).
                val bitmap = Bitmap.createBitmap(page.width * RENDER_DPI / 72, page.height * RENDER_DPI / 72, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                current = bitmap
                bitmap
            }
        }

        override fun close() {
            current?.recycle()
            renderer.close()
            fd.close()
        }
    }

    companion object {
        // Matches the printer's vertical resolution; horizontal is upscaled to 600 dpi by the driver.
        private const val RENDER_DPI = 300

        /** @throws IOException if the file cannot be read; [SecurityException] for a password-protected PDF. */
        fun open(context: Context, uri: Uri): PrintDocument {
            val resolver = context.contentResolver
            if (resolver.getType(uri) == "application/pdf") {
                val fd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $uri")
                return try {
                    Pdf(fd, PdfRenderer(fd))
                } catch (e: Exception) {
                    fd.close()
                    throw e
                }
            }
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // getPixels() needs a software bitmap
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(resolver, uri)
            }
            return Image(bitmap)
        }
    }
}
