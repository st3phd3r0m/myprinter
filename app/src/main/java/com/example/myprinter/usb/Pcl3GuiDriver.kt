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
import java.io.ByteArrayOutputStream

/**
 * PCL3GUI driver for the HP Deskjet 1510, mirroring what HPLIP's hpcups filter sends
 * for this model (tech-class "Pyramid", encapsulation Pcl3Gui2 in prnt/hpcups/Pcl3Gui2.cpp):
 * 24-bit RGB rows compressed with Mode 10, A4 plain paper, "Normal" quality (600x300 dpi).
 *
 * Validated against a reference stream produced on Linux by
 * `cupsfilter -p Deskjet-1510-series.ppd -m printer/foo`.
 */
class Pcl3GuiDriver {

    fun generatePclData(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width)
        return encodePage(bitmap.width, bitmap.height) { y, row ->
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            pixels.copyInto(row)
        }
    }

    /**
     * Encodes one page. The image ([imageWidth] x [imageHeight], ARGB pixels supplied row by row
     * through [readRow]) is scaled to fit the printable area, keeping its aspect ratio, and
     * centred horizontally at the top of the page.
     */
    internal fun encodePage(imageWidth: Int, imageHeight: Int, readRow: (y: Int, row: IntArray) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        writeJobHeader(out)
        writePageHeader(out)

        // Fit in inches, then convert to device pixels (horizontal and vertical dpi differ).
        val scale = minOf(
            PRINTABLE_WIDTH.toDouble() / H_DPI / imageWidth,
            PRINTABLE_HEIGHT.toDouble() / V_DPI / imageHeight
        )
        val dstWidth = (imageWidth * scale * H_DPI).toInt().coerceIn(1, PRINTABLE_WIDTH)
        val dstHeight = (imageHeight * scale * V_DPI).toInt().coerceIn(1, PRINTABLE_HEIGHT)
        val left = (PRINTABLE_WIDTH - dstWidth) / 2

        val compressor = Mode10Compressor(PRINTABLE_WIDTH)
        val source = IntArray(imageWidth)
        var sourceY = -1
        val line = IntArray(PRINTABLE_WIDTH)
        var skipped = 0

        for (y in 0 until PRINTABLE_HEIGHT) {
            line.fill(WHITE_RGB)
            if (y < dstHeight) {
                val sy = (y.toLong() * imageHeight / dstHeight).toInt()
                if (sy != sourceY) {
                    readRow(sy, source)
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
        out.write(RESET)
        out.write(UEL)
        return out.toByteArray()
    }

    private fun writeJobHeader(out: ByteArrayOutputStream) {
        out.write(RESET)
        out.write(UEL)
        out.write(
            ("@PJL SET STRINGCODESET=UTF8\n" +
                "@PJL JOBNAME=\"MyPrinter\"\n" +
                "@PJL SET JOBNAME=\"MyPrinter\"\n" +
                "@PJL ENTER LANGUAGE=PCL3GUI\n").toByteArray()
        )
    }

    private fun writePageHeader(out: ByteArrayOutputStream) {
        // Media source (7 = auto), media type (0 = plain), page size (26 = A4), print quality (0 = normal)
        out.write("\u001B&l7H\u001B&l0M\u001B&l26A\u001B*o0M".toByteArray())
        // Media subtype: Esc*o5W 0D 03 00 + 16-bit subtype (0)
        out.write(byteArrayOf(ESC, '*'.code.toByte(), 'o'.code.toByte(), '5'.code.toByte(), 'W'.code.toByte(), 0x0D, 0x03, 0x00, 0x00, 0x00))
        // Configure Raster Data: format 6, RGB only, 1 component, H/V resolution,
        // compression mode 10, orientation 1, 32 (24-bit RGB), 1 plane.
        out.write(
            byteArrayOf(
                ESC, '*'.code.toByte(), 'g'.code.toByte(), '1'.code.toByte(), '2'.code.toByte(), 'W'.code.toByte(),
                0x06, 0x07, 0x00, 0x01,
                (H_DPI shr 8).toByte(), H_DPI.toByte(), (V_DPI shr 8).toByte(), V_DPI.toByte(),
                0x0A, 0x01, 0x20, 0x01
            )
        )
        out.write("\u001B&u${V_DPI}D\u001B*t${V_DPI}R\u001B*r${PRINTABLE_WIDTH}S".toByteArray())
        out.write("\u001B&l-2H".toByteArray()) // media pre-load
        out.write("\u001B*r1A".toByteArray()) // start raster graphics
        out.write("\u001B*p${TOP_MARGIN}Y".toByteArray())
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
        private const val H_DPI = 600
        private const val V_DPI = 300

        // A4 imageable area of the hpcups PPD (HWMargins 9 41.04 9 4.32 pt) at 600x300 dpi.
        // Width is the value Ghostscript renders (cupsWidth); height is rounded down slightly.
        internal const val PRINTABLE_WIDTH = 4810
        internal const val PRINTABLE_HEIGHT = 3316

        // hpcups: printable_start_y (4 pt at 300 dpi = 16) minus HPMechOffset 60/1000 in (18).
        private const val TOP_MARGIN = -2
    }
}
