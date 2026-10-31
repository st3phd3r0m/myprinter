/*
 * Kotlin port of HPLIP prnt/hpcups/Mode10.cpp (hplip 3.23.12).
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

import java.io.ByteArrayOutputStream

/**
 * HP "Mode 10" (Bert) delta-row compression for 24-bit RGB rasters, as expected by
 * PCL3GUI printers such as the Deskjet 1510. Compresses one row per call against the
 * previous row (the seed row). Pixels are 0xRRGGBB ints; the blue LSB is dropped.
 */
class Mode10Compressor(private val width: Int) {

    private val seed = IntArray(width) { WHITE }

    init {
        require(width >= 2) { "Mode 10 needs at least 2 pixels per row" }
    }

    /** Resets the seed row to white. Must match the printer, which does the same after ESC*b#Y. */
    fun resetSeed() {
        seed.fill(WHITE)
    }

    /** Returns the compressed bytes for [row]; an empty array means "same as the seed row". */
    fun compress(row: IntArray): ByteArray {
        require(row.size == width)
        val cur = IntArray(width) { row[it] and WHITE }
        val out = ByteArrayOutputStream(width)
        val lastPixel = width - 1

        // Sentinel on the last pixel so the scanning loops below always stop.
        val realLastPixel = cur[lastPixel]
        var newLastPixel = realLastPixel
        while (cur[lastPixel - 1] == newLastPixel || seed[lastPixel] == newLastPixel) {
            newLastPixel += 0x100 // add one to green
            cur[lastPixel] = newLastPixel and WHITE
        }

        var curPixel = 0
        var cachedColor = WHITE

        do {
            var cmdByte: Int
            var replacementCount: Int
            var pixelSource: Int

            var seedRowPixelCopyCount = curPixel
            while (curPixel < lastPixel && seed[curPixel] == cur[curPixel]) {
                curPixel++
            }
            seedRowPixelCopyCount = curPixel - seedRowPixelCopyCount

            if (curPixel == lastPixel) {
                cur[lastPixel] = realLastPixel
                if (seed[curPixel] == realLastPixel) {
                    break
                }
                cmdByte = LITERAL
                pixelSource = SRC_NEW
                replacementCount = 1
                curPixel++
            } else {
                replacementCount = curPixel
                val rleRun = cur[curPixel]
                curPixel++
                while (curPixel <= lastPixel && rleRun == cur[curPixel]) {
                    curPixel++
                }
                curPixel--
                replacementCount = curPixel - replacementCount

                cmdByte = LITERAL
                pixelSource = SRC_NEW
                if (replacementCount > 0) {
                    curPixel++
                    replacementCount++
                    pixelSource = when {
                        cachedColor == rleRun -> SRC_CACHED
                        seed[curPixel - replacementCount + 1] == rleRun -> SRC_NE
                        curPixel - replacementCount > 0 && cur[curPixel - replacementCount - 1] == rleRun -> SRC_W
                        else -> {
                            cachedColor = rleRun
                            SRC_NEW
                        }
                    }
                    cmdByte = RLE
                }

                if (curPixel == lastPixel && realLastPixel == rleRun) {
                    cur[lastPixel] = realLastPixel
                    replacementCount++
                    curPixel++
                }

                if (replacementCount == 0) {
                    val tempPixel = cur[curPixel]
                    pixelSource = when {
                        cachedColor == tempPixel -> SRC_CACHED
                        seed[curPixel + 1] == tempPixel -> SRC_NE
                        curPixel > 0 && cur[curPixel - 1] == tempPixel -> SRC_W
                        else -> {
                            cachedColor = tempPixel
                            SRC_NEW
                        }
                    }

                    replacementCount = curPixel
                    var nextPixel = cur[curPixel + 1]
                    var cachePixel: Int
                    while (true) {
                        if (++curPixel == lastPixel) {
                            cur[lastPixel] = realLastPixel
                            curPixel++
                            break
                        }
                        cachePixel = nextPixel
                        nextPixel = cur[curPixel + 1]
                        if (cachePixel == nextPixel || cachePixel == seed[curPixel]) break
                    }
                    replacementCount = curPixel - replacementCount
                }
            }

            if (cmdByte == LITERAL) {
                out.write(cmdByte or pixelSource or (minOf(3, seedRowPixelCopyCount) shl 3) or minOf(7, replacementCount - 1))
                if (seedRowPixelCopyCount >= 3) writeVli(out, seedRowPixelCopyCount - 3)

                val total = replacementCount
                val start = curPixel - total
                // A pixel taken from W/NE/cache is not encoded; the rest always are.
                var upward = if (pixelSource == SRC_NEW) 1 else 2
                while (upward <= total) {
                    val p = start + upward - 1
                    writePixel(out, cur[p], seed[p])
                    if ((upward - 8) % 255 == 0) out.write(minOf(255, total - upward))
                    upward++
                }
            } else {
                out.write(cmdByte or pixelSource or (minOf(3, seedRowPixelCopyCount) shl 3) or minOf(7, replacementCount - 2))
                if (seedRowPixelCopyCount >= 3) writeVli(out, seedRowPixelCopyCount - 3)

                val start = curPixel - replacementCount
                if (pixelSource == SRC_NEW) writePixel(out, cur[start], seed[start])
                if (replacementCount - 2 >= 7) writeVli(out, replacementCount - 9)
            }
        } while (curPixel <= lastPixel)

        for (i in 0 until width) seed[i] = row[i] and WHITE
        return out.toByteArray()
    }

    private fun writePixel(out: ByteArrayOutputStream, pixel: Int, upperPixel: Int) {
        val dr = ((pixel shr 16) and 0xFF) - ((upperPixel shr 16) and 0xFF)
        val dg = ((pixel shr 8) and 0xFF) - ((upperPixel shr 8) and 0xFF)
        val db = (pixel and 0xFF) - (upperPixel and 0xFF)
        if (dr in -16..15 && dg in -16..15 && db in -32..30) {
            // Short delta; blue delta is halved to double its range.
            val delta = ((dr shl 10) and 0x7C00) or ((dg shl 5) and 0x03E0) or ((db shr 1) and 0x1F) or 0x8000
            out.write(delta shr 8)
            out.write(delta and 0xFF)
        } else {
            val packed = pixel ushr 1 // lose the blue LSB, clear the top bit
            out.write((packed shr 16) and 0xFF)
            out.write((packed shr 8) and 0xFF)
            out.write(packed and 0xFF)
        }
    }

    private fun writeVli(out: ByteArrayOutputStream, value: Int) {
        var number = value
        do {
            out.write(minOf(number, 255))
            if (number == 255) out.write(0)
            number -= minOf(number, 255)
        } while (number != 0)
    }

    companion object {
        const val WHITE = 0x00FFFFFE

        private const val LITERAL = 0x00
        private const val RLE = 0x80

        private const val SRC_NEW = 0x00
        private const val SRC_W = 0x20
        private const val SRC_NE = 0x40
        private const val SRC_CACHED = 0x60
    }
}
