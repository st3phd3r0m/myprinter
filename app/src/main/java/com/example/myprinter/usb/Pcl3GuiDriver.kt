/*
 * Job, page and raster sequences derived from HPLIP prnt/hpcups/Pcl3Gui2.cpp,
 * Encapsulator.cpp and PrinterCommands.h (hplip 3.23.12).
 *
 * Copyright (c) 1996 - 2015, HP Co.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of HP nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.  IN
 * NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PATENT INFRINGEMENT; PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.example.myprinter.usb

import android.graphics.Bitmap
import java.io.OutputStream

/** The part of [Bitmap] the driver reads (same contract as [Bitmap.getPixels], offset 0). */
internal interface PagePixels {
    val width: Int
    val height: Int
    fun getPixels(pixels: IntArray, stride: Int, x: Int, y: Int, width: Int, height: Int)
}

/** One page of ARGB pixels, supplied row by row. */
internal class PageImage(val width: Int, val height: Int, val readRow: (y: Int, row: IntArray) -> Unit)

/**
 * The hpcups PPD print modes for plain paper, with the values hpcups derives from them.
 * Printable area: A4 imageable area of the PPD (HWMargins 9 41.04 9 4.32 pt); the width is the
 * value Ghostscript renders (cupsWidth), the height is rounded down slightly.
 * Top margin: printable_start_y (4 pt) minus HPMechOffset (60/1000 in), at the vertical resolution.
 */
enum class PrintQuality(
    internal val pclQuality: Int,
    internal val hDpi: Int,
    internal val vDpi: Int,
    internal val printableWidth: Int,
    internal val printableHeight: Int,
    internal val topMargin: Int
) {
    Draft(-2, 300, 300, 2405, 3316, -2),
    Normal(0, 600, 300, 4810, 3316, -2),
    Best(1, 600, 600, 4810, 6632, -3)
}

/**
 * @param copies the whole document is repeated (collated copies).
 * @param landscape each page is rotated 90° counter-clockwise onto the portrait sheet.
 * @param grayscale printed with black ink only (hpcups "Black Only Grayscale").
 * @param pages zero-based indexes of the pages to print, in order; null for all pages.
 */
data class PrintOptions(
    val copies: Int = 1,
    val landscape: Boolean = false,
    val grayscale: Boolean = false,
    val quality: PrintQuality = PrintQuality.Normal,
    val pages: List<Int>? = null
)

/**
 * Parses a page selection such as "1-3, 5" for a document of [pageCount] pages.
 * Returns zero-based indexes in ascending order without duplicates, null for a blank text
 * (all pages), or throws [IllegalArgumentException] if a page is out of range or malformed.
 */
fun parsePageRanges(text: String, pageCount: Int): List<Int>? {
    if (text.isBlank()) return null
    val pages = sortedSetOf<Int>()
    for (part in text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
        val bounds = part.split('-').map { it.trim() }
        require(bounds.size in 1..2) { "Malformed range: $part" }
        val first = bounds[0].toIntOrNull() ?: throw IllegalArgumentException("Not a page: $part")
        val last = if (bounds.size == 2) bounds[1].toIntOrNull() ?: throw IllegalArgumentException("Not a page: $part") else first
        require(first in 1..last && last <= pageCount) { "Out of range: $part" }
        for (page in first..last) pages += page - 1
    }
    require(pages.isNotEmpty()) { "No page selected" }
    return pages.toList()
}

/** Page numbers to keep, e.g. to print both sides by hand: odd pages, then even ones. */
enum class PageParity { All, Odd, Even }

/**
 * [parsePageRanges] (blank meaning all pages), then keeps the pages of the given [parity]
 * (by page number, so Odd keeps pages 1, 3, 5...). Returns null for all pages; throws
 * [IllegalArgumentException] if the ranges are invalid or no page is left.
 */
fun selectPages(text: String, parity: PageParity, pageCount: Int): List<Int>? {
    val pages = parsePageRanges(text, pageCount)
    if (parity == PageParity.All) return pages
    val kept = (pages ?: (0 until pageCount).toList()).filter { index -> (index % 2 == 0) == (parity == PageParity.Odd) }
    require(kept.isNotEmpty()) { "No ${parity.name.lowercase()} page in the selection" }
    return kept
}

/**
 * PCL3GUI driver for the HP Deskjet 1510, mirroring what HPLIP's hpcups filter sends
 * for this model (tech-class "Pyramid", encapsulation Pcl3Gui2 in prnt/hpcups/Pcl3Gui2.cpp):
 * 24-bit RGB rows compressed with Mode 10, A4 plain paper, in one of the [PrintQuality] modes.
 *
 * Validated against reference streams produced on Linux by
 * `cupsfilter -p Deskjet-1510-series.ppd -m printer/foo`.
 */
class Pcl3GuiDriver {

    /**
     * Writes a print job of [pageCount] pages to [out]. Pages are rendered one at a time by
     * [renderPage], so only one page bitmap needs to be in memory; the caller owns the bitmaps.
     */
    fun writeJob(out: OutputStream, pageCount: Int, options: PrintOptions, renderPage: (index: Int) -> Bitmap) {
        writeLaidOutJob(out, pageCount, options) { index ->
            val bitmap = renderPage(index)
            object : PagePixels {
                override val width = bitmap.width
                override val height = bitmap.height
                override fun getPixels(pixels: IntArray, stride: Int, x: Int, y: Int, width: Int, height: Int) =
                    bitmap.getPixels(pixels, 0, stride, x, y, width, height)
            }
        }
    }

    /** Applies page selection, copies and orientation; separate from [Bitmap] so it can be unit tested. */
    internal fun writeLaidOutJob(out: OutputStream, pageCount: Int, options: PrintOptions, renderPage: (index: Int) -> PagePixels) {
        require(options.copies > 0)
        val pages = options.pages ?: (0 until pageCount).toList()
        require(pages.isNotEmpty() && pages.all { it in 0 until pageCount })
        writePages(out, pages.size * options.copies, options.quality, options.grayscale) { index ->
            val page = renderPage(pages[index % pages.size])
            if (options.landscape) {
                // Row y of the rotated page is column (width - 1 - y) of the page, read top to bottom.
                PageImage(page.height, page.width) { y, row ->
                    page.getPixels(row, 1, page.width - 1 - y, 0, 1, page.height)
                }
            } else {
                PageImage(page.width, page.height) { y, row ->
                    page.getPixels(row, page.width, 0, y, page.width, 1)
                }
            }
        }
    }

    /** hpcups sends the job header once, then the same page header before every page. */
    internal fun writePages(
        out: OutputStream,
        pageCount: Int,
        quality: PrintQuality = PrintQuality.Normal,
        grayscale: Boolean = false,
        page: (index: Int) -> PageImage
    ) {
        require(pageCount > 0)
        writeJobHeader(out)
        for (index in 0 until pageCount) {
            writePage(out, page(index), quality, grayscale)
        }
        out.write(RESET)
        out.write(UEL)
    }

    /**
     * The image is scaled to fit the printable area, keeping its aspect ratio, and centred
     * horizontally at the top of the page.
     */
    private fun writePage(out: OutputStream, image: PageImage, quality: PrintQuality, grayscale: Boolean) {
        val (imageWidth, imageHeight) = image.width to image.height
        val (printableWidth, printableHeight) = quality.printableWidth to quality.printableHeight
        writePageHeader(out, quality, grayscale)

        // Fit in inches, then convert to device pixels (horizontal and vertical dpi may differ).
        val scale = minOf(
            printableWidth.toDouble() / quality.hDpi / imageWidth,
            printableHeight.toDouble() / quality.vDpi / imageHeight
        )
        val dstWidth = (imageWidth * scale * quality.hDpi).toInt().coerceIn(1, printableWidth)
        val dstHeight = (imageHeight * scale * quality.vDpi).toInt().coerceIn(1, printableHeight)
        val left = (printableWidth - dstWidth) / 2

        val compressor = Mode10Compressor(printableWidth)
        val source = IntArray(imageWidth)
        var sourceY = -1
        val line = IntArray(printableWidth)
        var skipped = 0

        for (y in 0 until printableHeight) {
            line.fill(WHITE_RGB)
            if (y < dstHeight) {
                val sy = (y.toLong() * imageHeight / dstHeight).toInt()
                if (sy != sourceY) {
                    image.readRow(sy, source)
                    sourceY = sy
                }
                for (x in 0 until dstWidth) {
                    line[left + x] = onWhite(source[(x.toLong() * imageWidth / dstWidth).toInt()])
                }
            }
            // hpcups marks the first row as non-blank: the printer may otherwise
            // not skip the blank rows that precede the data.
            if (y == 0) line[0] = line[0] and 0x00FFFF or 0x010000

            if (line.all { it == WHITE_RGB }) {
                skipped++
                continue
            }
            if (skipped > 0) {
                out.write("\u001B*b${skipped}Y".toByteArray())
                compressor.resetSeed()
                skipped = 0
            }
            val data = compressor.compress(line)
            out.write("\u001B*b${data.size}W".toByteArray())
            out.write(data)
        }
        // Trailing blank rows are not sent, as in hpcups.

        out.write(byteArrayOf(ESC, '*'.code.toByte(), 'r'.code.toByte(), 'C'.code.toByte(), 0x0C)) // end raster, form feed
    }

    private fun writeJobHeader(out: OutputStream) {
        out.write(RESET)
        out.write(UEL)
        out.write(
            ("@PJL SET STRINGCODESET=UTF8\n" +
                "@PJL JOBNAME=\"MyPrinter\"\n" +
                "@PJL SET JOBNAME=\"MyPrinter\"\n" +
                "@PJL ENTER LANGUAGE=PCL3GUI\n").toByteArray()
        )
    }

    private fun writePageHeader(out: OutputStream, quality: PrintQuality, grayscale: Boolean) {
        val (hDpi, vDpi) = quality.hDpi to quality.vDpi
        // Media source (7 = auto), media type (0 = plain), page size (26 = A4), print quality
        out.write("\u001B&l7H\u001B&l0M\u001B&l26A\u001B*o${quality.pclQuality}M".toByteArray())
        // Media subtype: Esc*o5W 0D 03 00 + 16-bit subtype (0)
        out.write(byteArrayOf(ESC, '*'.code.toByte(), 'o'.code.toByte(), '5'.code.toByte(), 'W'.code.toByte(), 0x0D, 0x03, 0x00, 0x00, 0x00))
        // Configure Raster Data: format 6, RGB only, 1 component, H/V resolution,
        // compression mode 10, orientation 1, 32 (24-bit RGB), 1 plane.
        out.write(
            byteArrayOf(
                ESC, '*'.code.toByte(), 'g'.code.toByte(), '1'.code.toByte(), '2'.code.toByte(), 'W'.code.toByte(),
                0x06, 0x07, 0x00, 0x01,
                (hDpi shr 8).toByte(), hDpi.toByte(), (vDpi shr 8).toByte(), vDpi.toByte(),
                0x0A, 0x01, 0x20, 0x01
            )
        )
        if (grayscale) {
            // Esc*o5W 0B 01 00 00 + colour mode (2 = black only); rows stay RGB, the printer converts.
            out.write(byteArrayOf(ESC, '*'.code.toByte(), 'o'.code.toByte(), '5'.code.toByte(), 'W'.code.toByte(), 0x0B, 0x01, 0x00, 0x00, 0x02))
        }
        // Unit of measure is the lower of the two resolutions (hpcups).
        out.write("\u001B&u${minOf(hDpi, vDpi)}D\u001B*t${vDpi}R\u001B*r${quality.printableWidth}S".toByteArray())
        out.write("\u001B&l-2H".toByteArray()) // media pre-load
        out.write("\u001B*r1A".toByteArray()) // start raster graphics
        out.write("\u001B*p${quality.topMargin}Y".toByteArray())
    }

    /** Composites an ARGB pixel on white paper and returns 0xRRGGBB. */
    private fun onWhite(argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        if (a == 0xFF) return argb and 0xFFFFFF
        fun blend(c: Int) = (c * a + 0xFF * (0xFF - a)) / 0xFF
        return (blend((argb shr 16) and 0xFF) shl 16) or (blend((argb shr 8) and 0xFF) shl 8) or blend(argb and 0xFF)
    }

    companion object {
        private const val ESC: Byte = 0x1B
        private val RESET = byteArrayOf(ESC, 'E'.code.toByte())
        private val UEL = "\u001B%-12345X".toByteArray()

        private const val WHITE_RGB = 0xFFFFFF
    }
}
