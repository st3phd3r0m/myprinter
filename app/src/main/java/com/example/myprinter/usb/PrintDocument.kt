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

    /**
     * The returned bitmap stays valid until the next call or [close].
     * [dpi] applies to PDF pages; images are returned at their own resolution.
     */
    abstract fun renderPage(index: Int, dpi: Int = PRINT_DPI): Bitmap

    private class Image(private val bitmap: Bitmap) : PrintDocument() {
        override val pageCount = 1
        override fun renderPage(index: Int, dpi: Int) = bitmap
        override fun close() = bitmap.recycle()
    }

    private class Pdf(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) : PrintDocument() {
        private var current: Bitmap? = null

        override val pageCount = renderer.pageCount

        override fun renderPage(index: Int, dpi: Int): Bitmap {
            // Free the previous page first: a 300 dpi A4 page is ~35 MB.
            current?.recycle()
            current = null
            return renderer.openPage(index).use { page ->
                // PDF page sizes are in points (1/72 inch).
                val bitmap = Bitmap.createBitmap(page.width * dpi / 72, page.height * dpi / 72, Bitmap.Config.ARGB_8888)
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
        // Matches the printer's vertical resolution in Draft and Normal; the driver rescales as needed.
        private const val PRINT_DPI = 300

        fun isPdf(context: Context, uri: Uri) = context.contentResolver.getType(uri) == "application/pdf"

        /** @throws IOException if the file cannot be read; [SecurityException] for a password-protected PDF. */
        fun open(context: Context, uri: Uri): PrintDocument {
            val resolver = context.contentResolver
            if (isPdf(context, uri)) {
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
