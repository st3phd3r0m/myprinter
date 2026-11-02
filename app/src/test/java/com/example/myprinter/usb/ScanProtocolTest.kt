package com.example.myprinter.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.imageio.ImageIO

class ScanProtocolTest {

    /** Answers each request (its text up to the body) once it is flushed, as the USB device would. */
    private class FakeDevice(private val answer: (String) -> ByteArray) {
        val requests = mutableListOf<String>()
        private val request = ByteArrayOutputStream()
        private var pending: InputStream = ByteArrayInputStream(ByteArray(0))

        val channel = HttpChannel(
            object : InputStream() {
                override fun read() = pending.read()
                override fun read(b: ByteArray, off: Int, len: Int) = pending.read(b, off, len)
            },
            object : OutputStream() {
                override fun write(b: Int) = request.write(b)
                override fun flush() {
                    val text = request.toString(Charsets.ISO_8859_1.name())
                    request.reset()
                    requests += text
                    pending = ByteArrayInputStream(answer(text))
                }
            }
        )
    }

    private fun response(status: Int, body: ByteArray = ByteArray(0), headers: String = "", chunked: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("HTTP/1.1 $status X\r\n$headers".toByteArray())
        if (chunked) {
            out.write("Transfer-Encoding: chunked\r\n\r\n".toByteArray())
            // Two chunks, to check they are joined.
            val half = body.size / 2
            for (part in listOf(body.copyOfRange(0, half), body.copyOfRange(half, body.size))) {
                out.write("${Integer.toHexString(part.size)}\r\n".toByteArray())
                out.write(part)
                out.write("\r\n".toByteArray())
            }
            out.write("0\r\n\r\n".toByteArray())
        } else {
            out.write("Content-Length: ${body.size}\r\n\r\n".toByteArray())
            out.write(body)
        }
        return out.toByteArray()
    }

    private val image = ByteArray(5000) { it.toByte() }

    @Test
    fun ledm_scansAPage() {
        var jobPolls = 0
        val device = FakeDevice { request ->
            val line = request.substringBefore("\r\n")
            when (line) {
                "GET /eSCL/ScannerCapabilities HTTP/1.1" -> response(404)
                "GET /Scan/ScanCaps HTTP/1.1" -> response(200, chunked = true, body =
                    ("<ScanCaps><Platen><InputSourceCaps><MinWidth>8</MinWidth><MinHeight>8</MinHeight>" +
                        "<MaxWidth>2550</MaxWidth><MaxHeight>3500</MaxHeight></InputSourceCaps></Platen></ScanCaps>").toByteArray())
                "GET /Scan/Status HTTP/1.1" -> response(200, "<ScanStatus><ScannerState>Idle</ScannerState></ScanStatus>".toByteArray())
                "POST /Scan/Jobs HTTP/1.1" -> response(201, headers = "Location: http://localhost/Jobs/JobList/2\r\n")
                "GET /Jobs/JobList/2 HTTP/1.1" -> {
                    val state = if (jobPolls++ == 0) "PreparingScan" else "ReadyToUpload"
                    // Stale bytes before the status line are skipped.
                    "garbage\r\n".toByteArray() + response(200, chunked = true, body =
                        ("<j:Job><j:JobState>Processing</j:JobState><ScanJob><PreScanPage><PageState>$state</PageState>" +
                            "<BinaryURL>/Scan/Jobs/2/Pages/1</BinaryURL></PreScanPage></ScanJob></j:Job>").toByteArray())
                }
                "GET /Scan/Jobs/2/Pages/1 HTTP/1.1" -> response(200, image, chunked = true)
                else -> error("Unexpected $line")
            }
        }

        val protocol = ScanProtocol.detect(device.channel)
        assertEquals(ScanProtocol.Ledm, protocol)
        val page = protocol!!.scanPage(device.channel, ScanOptions(ScanColor.Gray, 150)) { }

        assertArrayEquals(image, page)
        assertEquals(2, jobPolls)
        val post = device.requests.single { it.startsWith("POST") }
        // A4 width, the glass's height (smaller than A4).
        assertTrue(post.contains("<Width>2480</Width>") && post.contains("<Height>3500</Height>"))
        assertTrue(post.contains("<XResolution>150</XResolution>") && post.contains("<ColorSpace>Gray</ColorSpace>"))
        val body = post.substringAfter("\r\n\r\n")
        assertEquals(body.length, Regex("Content-Length: (\\d+)").find(post)!!.groupValues[1].toInt())
    }

    @Test
    fun ledm_reportsAnEmptyJob() {
        val device = FakeDevice { request ->
            when (request.substringBefore(" HTTP")) {
                "GET /Scan/ScanCaps" -> response(200, "<ScanCaps/>".toByteArray())
                "GET /Scan/Status" -> response(200, "<ScannerState>Idle</ScannerState>".toByteArray())
                "POST /Scan/Jobs" -> response(201, headers = "Location: /Jobs/JobList/3\r\n")
                else -> response(200, "<j:Job><j:JobState>Processing</j:JobState></j:Job>".toByteArray())
            }
        }
        val error = runCatching { ScanProtocol.Ledm.scanPage(device.channel, ScanOptions()) { } }.exceptionOrNull()
        assertEquals(ScanException.Reason.NoDocument, (error as ScanException).reason)
    }

    @Test
    fun ledm_refusesWhenBusy() {
        val device = FakeDevice { request ->
            if (request.startsWith("GET /Scan/Status")) response(200, "<ScannerState>BusyWithScanJob</ScannerState>".toByteArray())
            else response(200, "<ScanCaps/>".toByteArray())
        }
        val error = runCatching { ScanProtocol.Ledm.scanPage(device.channel, ScanOptions()) { } }.exceptionOrNull()
        assertEquals(ScanException.Reason.Busy, (error as ScanException).reason)
    }

    @Test
    fun escl_scansAPage() {
        var pagePolls = 0
        val device = FakeDevice { request ->
            when (val line = request.substringBefore("\r\n")) {
                "GET /eSCL/ScannerCapabilities HTTP/1.1" -> response(200,
                    ("<scan:ScannerCapabilities><scan:Platen><scan:PlatenInputCaps><scan:MaxWidth>2550</scan:MaxWidth>" +
                        "<scan:MaxHeight>4200</scan:MaxHeight></scan:PlatenInputCaps></scan:Platen></scan:ScannerCapabilities>").toByteArray())
                "GET /eSCL/ScannerStatus HTTP/1.1" -> response(200, "<scan:ScannerStatus><pwg:State>Idle</pwg:State></scan:ScannerStatus>".toByteArray())
                "POST /eSCL/ScanJobs HTTP/1.1" -> response(201, headers = "Location: http://localhost:60000/eSCL/ScanJobs/ab-12/\r\n")
                "GET /eSCL/ScanJobs/ab-12/NextDocument HTTP/1.1" -> if (pagePolls++ < 2) response(503) else response(200, image)
                else -> error("Unexpected $line")
            }
        }

        assertEquals(ScanProtocol.Escl, ScanProtocol.detect(device.channel))
        val page = ScanProtocol.Escl.scanPage(device.channel, ScanOptions(ScanColor.Color, 300)) { }

        assertArrayEquals(image, page)
        val post = device.requests.single { it.startsWith("POST") }
        assertTrue(post.contains("<pwg:Width>2480</pwg:Width>") && post.contains("<pwg:Height>3508</pwg:Height>"))
        assertTrue(post.contains("<scan:ColorMode>RGB24</scan:ColorMode>"))
    }

    private fun realJpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg", out)
        return out.toByteArray()
    }

    @Test
    fun jpeg_readsSizeAndMovesTheDnlHeightIntoTheFrameHeader() {
        val jpeg = realJpeg(120, 70)
        val info = Jpeg.info(jpeg)
        assertEquals(120, info.width)
        assertEquals(70, info.height)
        assertEquals(3, info.components)
        assertTrue(Jpeg.withKnownHeight(jpeg) === jpeg)

        // As streamed by a scanner: height 0 in SOF, DNL with the line count before EOI.
        val sof = (2 until jpeg.size).first { jpeg[it] == 0xFF.toByte() && jpeg[it + 1] == 0xC0.toByte() }
        val streamed = jpeg.copyOf()
        streamed[sof + 5] = 0
        streamed[sof + 6] = 0
        val withDnl = streamed.copyOfRange(0, streamed.size - 2) +
            byteArrayOf(0xFF.toByte(), 0xDC.toByte(), 0, 4, 0, 70, 0xFF.toByte(), 0xD9.toByte())
        assertEquals(0, Jpeg.info(withDnl).height)
        assertEquals(70, Jpeg.info(Jpeg.withKnownHeight(withDnl)).height)
    }

    @Test
    fun pdf_embedsEachJpegAsAPage() {
        val dir = createTempDir()
        try {
            val first = File(dir, "1.jpg").apply { writeBytes(realJpeg(2480, 3508)) }
            val second = File(dir, "2.jpg").apply { writeBytes(realJpeg(150, 100)) }
            val out = ByteArrayOutputStream()
            JpegPdf.write(out, listOf(ScannedPage(first, 300), ScannedPage(second, 150)))
            val pdf = out.toByteArray()
            val text = String(pdf, Charsets.ISO_8859_1)

            assertTrue(text.startsWith("%PDF-1.4"))
            assertTrue(text.contains("/Count 2"))
            assertTrue(text.contains("/MediaBox[0 0 595.20 841.92]")) // A4 at 300 dpi
            assertTrue(text.contains("/MediaBox[0 0 72.00 48.00]"))
            assertTrue(text.contains("/Length ${first.length()}>>"))

            // Every xref entry points at its object.
            val xref = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
            val entries = text.substring(xref).lines().drop(3).takeWhile { it.endsWith(" n ") }
            assertEquals(8, entries.size)
            entries.forEachIndexed { index, entry ->
                val offset = entry.substringBefore(' ').toInt()
                assertTrue(text.startsWith("${index + 1} 0 obj\n", offset))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
