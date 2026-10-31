package com.example.myprinter.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class Pcl3GuiDriverTest {

    @Test
    fun mode10_roundTripsVariedRows() {
        val width = 4810
        val random = Random(1510)
        val compressor = Mode10Compressor(width)
        var seed = IntArray(width) { Mode10Compressor.WHITE }

        val rows = listOf(
            IntArray(width) { 0xFFFFFF },                                       // blank
            IntArray(width) { if (it in 300..900) 0x000000 else 0xFFFFFF },     // black bar
            IntArray(width) { if (it in 300..900) 0x000000 else 0xFFFFFF },     // same row again
            IntArray(width) { if (it in 1200..1500) 0xFF0000 else 0xFFFFFF },   // red block
            IntArray(width) { (it * 255 / width) * 0x010101 },                  // grey gradient (short deltas)
            IntArray(width) { random.nextInt(0x1000000) },                      // noise (raw literals)
            IntArray(width) { if (random.nextInt(10) == 0) random.nextInt(0x1000000) else 0x123456 },
            IntArray(width) { if (it % 2 == 0) 0x000000 else 0xFFFFFF },        // alternating
            IntArray(width) { if (it == width - 1) 0x000000 else 0xFFFFFF },    // last pixel only
            IntArray(width) { 0x000000 },                                       // full black
        )
        for ((index, row) in rows.withIndex()) {
            val compressed = compressor.compress(row)
            val decoded = Mode10Decoder.decodeRow(compressed, seed)
            assertArrayEquals("row $index", IntArray(width) { row[it] and Mode10Compressor.WHITE }, decoded)
            seed = decoded
        }
    }

    @Test
    fun pageHeader_matchesHpcupsReference() {
        val page = Pcl3GuiDriver().encodePage(10, 10) { _, row -> row.fill(0xFF000000.toInt()) }
        val start = page.indexOf("@PJL ENTER LANGUAGE=PCL3GUI\n".toByteArray()) + 28
        // Bytes captured from hpcups output for the Deskjet 1510 (A4, plain, normal, colour).
        val expected = "1b266c37481b266c304d1b266c3236411b2a6f304d1b2a6f3557" +
            "0d030000001b2a67313257060700010258012c0a0120011b2675333030441b2a74333030521b2a72343831305" +
            "31b266c2d32481b2a7231411b2a702d3259"
        val header = page.copyOfRange(start, start + expected.length / 2)
        assertEquals(expected, header.joinToString("") { "%02x".format(it) })
        assertTrue(page.endsWith(byteArrayOf(0x1B, '*'.code.toByte(), 'r'.code.toByte(), 'C'.code.toByte(), 0x0C, 0x1B, 'E'.code.toByte()) + "\u001B%-12345X".toByteArray()))
    }

    @Test
    fun encodePage_writesSamplePageForExternalCheck() {
        // A 4:3 image: black frame, red/green/blue squares, grey gradient band.
        val w = 400
        val h = 300
        val page = Pcl3GuiDriver().encodePage(w, h) { y, row ->
            for (x in 0 until w) {
                row[x] = when {
                    x < 8 || y < 8 || x >= w - 8 || y >= h - 8 -> 0xFF000000.toInt()
                    y in 40..120 && x in 40..120 -> 0xFFFF0000.toInt()
                    y in 40..120 && x in 160..240 -> 0xFF00FF00.toInt()
                    y in 40..120 && x in 280..360 -> 0xFF0000FF.toInt()
                    y in 180..260 -> (0xFF shl 24) or ((x * 255 / w) * 0x010101)
                    else -> 0x00000000 // transparent: printed as white paper
                }
            }
        }
        File("build/test-output").mkdirs()
        File("build/test-output/sample_page.pcl").writeBytes(page)

        val rows = Mode10Decoder.decodePage(page)
        assertEquals(Pcl3GuiDriver.PRINTABLE_WIDTH, rows.first().size)
        // The image is 4:3 and fits the page width: 4810 px at 600 dpi -> 1803 rows at 300 dpi.
        val left = rows[100].indexOfFirst { it == 0 }
        assertEquals(0, left)
        assertEquals(0xFF0000 and Mode10Compressor.WHITE, rows[300][1200])
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int =
        (0..size - needle.size).first { i -> needle.indices.all { this[i + it] == needle[it] } }

    private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
        size >= suffix.size && copyOfRange(size - suffix.size, size).contentEquals(suffix)
}

/** Independent Mode 10 decoder, written from the format rather than from the encoder. */
private object Mode10Decoder {
    private const val WHITE = Mode10Compressor.WHITE

    fun decodePage(stream: ByteArray): List<IntArray> {
        val text = String(stream, Charsets.ISO_8859_1)
        val width = Regex("\u001B\\*r(\\d+)S").find(text)!!.groupValues[1].toInt()
        var seed = IntArray(width) { WHITE }
        val rows = mutableListOf<IntArray>()
        val command = Regex("\u001B\\*b(\\d+)([WY])")
        var pos = text.indexOf("\u001B*r1A")
        while (true) {
            val m = command.find(text, pos) ?: break
            val n = m.groupValues[1].toInt()
            pos = m.range.last + 1
            if (m.groupValues[2] == "Y") {
                repeat(n) { rows.add(IntArray(width) { WHITE }) }
                seed = IntArray(width) { WHITE }
            } else {
                seed = decodeRow(stream.copyOfRange(pos, pos + n), seed)
                rows.add(seed)
                pos += n
            }
        }
        return rows
    }

    fun decodeRow(data: ByteArray, seed: IntArray): IntArray {
        val width = seed.size
        val cur = seed.copyOf()
        var pos = 0
        var x = 0
        var cache = WHITE
        fun byte() = data[pos++].toInt() and 0xFF
        fun vli(): Int {
            var total = 0
            while (true) {
                val b = byte()
                total += b
                if (b != 255) return total
            }
        }
        fun pixel(at: Int): Int {
            val b0 = byte()
            val upper = if (at < width) seed[at] else WHITE
            if (b0 and 0x80 != 0) {
                val v = (b0 shl 8) or byte()
                fun signed5(s: Int) = if (s and 0x10 != 0) s - 32 else s
                val r = ((upper shr 16) and 0xFF) + signed5((v shr 10) and 0x1F)
                val g = ((upper shr 8) and 0xFF) + signed5((v shr 5) and 0x1F)
                val b = (upper and 0xFF) + signed5(v and 0x1F) * 2
                return ((r and 0xFF) shl 16 or ((g and 0xFF) shl 8) or (b and 0xFF)) and WHITE
            }
            return (((b0 shl 16) or (byte() shl 8) or byte()) shl 1) and WHITE
        }

        while (pos < data.size && x < width) {
            val cmd = byte()
            val source = cmd and 0x60
            var seedCount = (cmd shr 3) and 0x03
            if (seedCount == 3) seedCount += vli()
            x += seedCount
            val first = when (source) {
                0x00 -> pixel(x).also { cache = it }
                0x20 -> cur[x - 1]
                0x40 -> seed[x + 1]
                else -> cache
            }
            if (cmd and 0x80 != 0) {
                var count = (cmd and 0x07) + 2
                if (cmd and 0x07 == 7) count += vli()
                repeat(count) { if (x < width) cur[x] = first; x++ }
            } else {
                var total: Int? = if (cmd and 0x07 < 7) (cmd and 0x07) + 1 else null
                cur[x++] = first
                var done = 1
                while (total == null || done < total) {
                    val p = pixel(x)
                    if (x < width) cur[x] = p
                    x++
                    done++
                    if (total == null && done >= 8 && (done - 8) % 255 == 0) {
                        val extra = byte()
                        if (extra != 255) total = done + extra
                    }
                }
            }
        }
        return cur
    }
}
