package com.example.myprinter.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
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
        val page = encode(blackPage)
        val start = page.indexOf("@PJL ENTER LANGUAGE=PCL3GUI\n".toByteArray()) + 28
        val header = page.copyOfRange(start, start + PAGE_HEADER_HEX.length / 2)
        assertEquals(PAGE_HEADER_HEX, header.joinToString("") { "%02x".format(it) })
        assertTrue(page.endsWith(PAGE_END + JOB_END))
    }

    @Test
    fun multiPageJob_matchesHpcupsStructure() {
        // hpcups (3-page reference): one job header, then the same page header and
        // page end for every page, then Reset + UEL once.
        val job = String(encode(blackPage, blackPage, blackPage), Charsets.ISO_8859_1)
        val pageHeader = String(PAGE_HEADER_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.ISO_8859_1)
        assertEquals(1, job.windowed(27).count { it == "@PJL ENTER LANGUAGE=PCL3GUI" })
        assertEquals(3, job.split(pageHeader).size - 1)
        assertEquals(3, job.split(String(PAGE_END, Charsets.ISO_8859_1)).size - 1)
        assertTrue(job.endsWith(String(PAGE_END + JOB_END, Charsets.ISO_8859_1)))
    }

    @Test
    fun grayscaleHeader_matchesHpcupsKGrayReference() {
        val out = ByteArrayOutputStream()
        Pcl3GuiDriver().writePages(out, 1, grayscale = true) { blackPage }
        val page = out.toByteArray()
        val start = page.indexOf("@PJL ENTER LANGUAGE=PCL3GUI\n".toByteArray()) + 28
        // hpcups output with -o ColorModel=KGray ("Black Only Grayscale").
        val expected = "1b266c37481b266c304d1b266c3236411b2a6f304d1b2a6f35570d030000001b2a67313257060700010258012c" +
            "0a0120011b2a6f35570b010000021b2675333030441b2a74333030521b2a7234383130531b266c2d32481b2a7231411b2a702d3259"
        assertEquals(expected, page.copyOfRange(start, start + expected.length / 2).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun qualityHeaders_matchHpcupsReferences() {
        // hpcups output with -o OutputMode=Draft / Normal / Best.
        val expected = mapOf(
            PrintQuality.Draft to "1b266c37481b266c304d1b266c3236411b2a6f2d324d1b2a6f35570d030000001b2a6731325706070001012c012c" +
                "0a0120011b2675333030441b2a74333030521b2a7232343035531b266c2d32481b2a7231411b2a702d3259",
            PrintQuality.Normal to PAGE_HEADER_HEX,
            PrintQuality.Best to "1b266c37481b266c304d1b266c3236411b2a6f314d1b2a6f35570d030000001b2a6731325706070001025802580a" +
                "0120011b2675363030441b2a74363030521b2a7234383130531b266c2d32481b2a7231411b2a702d3359"
        )
        for ((quality, hex) in expected) {
            val out = ByteArrayOutputStream()
            Pcl3GuiDriver().writePages(out, 1, quality) { blackPage }
            val page = out.toByteArray()
            val start = page.indexOf("@PJL ENTER LANGUAGE=PCL3GUI\n".toByteArray()) + 28
            assertEquals(quality.name, hex, page.copyOfRange(start, start + hex.length / 2).joinToString("") { "%02x".format(it) })

            // The square image fills the printable width; its height in rows follows the vertical dpi.
            val rows = Mode10Decoder.decodePage(page)
            assertEquals(quality.printableWidth, rows.first().size)
            val expectedRows = quality.printableWidth * quality.vDpi / quality.hDpi
            assertTrue("${quality.name}: ${rows.size} rows", rows.size in expectedRows - 1..expectedRows)
        }
    }

    @Test
    fun landscape_rotatesPageCounterClockwise() {
        // 400x200 landscape page: top half red, bottom half blue, black stripe on the left edge.
        val page = FakePage(400, 200) { x, y ->
            when {
                x < 20 -> 0xFF000000.toInt()
                y < 100 -> 0xFFFF0000.toInt()
                else -> 0xFF0000FF.toInt()
            }
        }
        val out = ByteArrayOutputStream()
        Pcl3GuiDriver().writeLaidOutJob(out, 1, PrintOptions(landscape = true)) { page }
        val rows = Mode10Decoder.decodePage(out.toByteArray())

        // Rotated 200x400 page fills the printable height: 3316 x 3316 px, centred (left = 747).
        // Counter-clockwise: the top of the page goes to the left, its left edge to the bottom.
        val red = 0xFF0000 and Mode10Compressor.WHITE
        val blue = 0x0000FF and Mode10Compressor.WHITE
        assertEquals(red, rows[100][747 + 100])
        assertEquals(blue, rows[100][747 + 3000])
        assertEquals(0, rows[3300][747 + 1500])
        assertEquals(Mode10Compressor.WHITE, rows[100][700]) // left margin
    }

    @Test
    fun copies_repeatTheWholeDocument() {
        val rendered = mutableListOf<Int>()
        val out = ByteArrayOutputStream()
        Pcl3GuiDriver().writeLaidOutJob(out, 2, PrintOptions(copies = 2)) { index ->
            rendered += index
            FakePage(10, 10) { _, _ -> 0xFF000000.toInt() }
        }
        assertEquals(listOf(0, 1, 0, 1), rendered)
        val job = String(out.toByteArray(), Charsets.ISO_8859_1)
        assertEquals(4, job.split(String(PAGE_END, Charsets.ISO_8859_1)).size - 1)
    }

    @Test
    fun encodePage_writesSamplePageForExternalCheck() {
        // A 4:3 image: black frame, red/green/blue squares, grey gradient band.
        val w = 400
        val h = 300
        val page = encode(PageImage(w, h) { y, row ->
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
        })
        File("build/test-output").mkdirs()
        File("build/test-output/sample_page.pcl").writeBytes(page)

        val rows = Mode10Decoder.decodePage(page)
        assertEquals(PrintQuality.Normal.printableWidth, rows.first().size)
        // The image is 4:3 and fits the page width: 4810 px at 600 dpi -> 1803 rows at 300 dpi.
        val left = rows[100].indexOfFirst { it == 0 }
        assertEquals(0, left)
        assertEquals(0xFF0000 and Mode10Compressor.WHITE, rows[300][1200])
    }

    private val blackPage = PageImage(10, 10) { _, row -> row.fill(0xFF000000.toInt()) }

    private fun encode(vararg pages: PageImage): ByteArray =
        ByteArrayOutputStream().also { out -> Pcl3GuiDriver().writePages(out, pages.size) { pages[it] } }.toByteArray()

    private fun ByteArray.indexOf(needle: ByteArray): Int =
        (0..size - needle.size).first { i -> needle.indices.all { this[i + it] == needle[it] } }

    private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
        size >= suffix.size && copyOfRange(size - suffix.size, size).contentEquals(suffix)

    private companion object {
        // Bytes captured from hpcups output for the Deskjet 1510 (A4, plain, normal, colour),
        // from the page-size command to the top-margin command.
        const val PAGE_HEADER_HEX = "1b266c37481b266c304d1b266c3236411b2a6f304d1b2a6f3557" +
            "0d030000001b2a67313257060700010258012c0a0120011b2675333030441b2a74333030521b2a72343831305" +
            "31b266c2d32481b2a7231411b2a702d3259"
        val PAGE_END = byteArrayOf(0x1B, '*'.code.toByte(), 'r'.code.toByte(), 'C'.code.toByte(), 0x0C)
        val JOB_END = byteArrayOf(0x1B, 'E'.code.toByte()) + "\u001B%-12345X".toByteArray()
    }
}

/** In-memory page following the [android.graphics.Bitmap.getPixels] contract. */
private class FakePage(override val width: Int, override val height: Int, val pixel: (x: Int, y: Int) -> Int) : PagePixels {
    override fun getPixels(pixels: IntArray, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        for (j in 0 until height) for (i in 0 until width) pixels[j * stride + i] = pixel(x + i, y + j)
    }
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
