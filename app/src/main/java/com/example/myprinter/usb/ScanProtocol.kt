/*
 * The LEDM scan sequence follows HPLIP's scan/sane/bb_ledm.c:
 * (c) 2010 Copyright HP Development Company, LP. BSD-3-Clause, see NOTICE.
 */
package com.example.myprinter.usb

import java.io.IOException
import java.net.URI

enum class ScanColor { Color, Gray }

data class ScanOptions(val color: ScanColor = ScanColor.Color, val dpi: Int = 300)

class ScanException(val reason: Reason, message: String) : IOException(message) {
    enum class Reason { Busy, NoDocument, Failed }
}

/**
 * The scanning protocols spoken over HTTP on a USB interface. Each scans one flatbed page,
 * A4 or the whole glass if smaller, and returns it as JPEG.
 */
enum class ScanProtocol {
    /** HP's own protocol (Deskjet 1510 and other HP multifunctions of the 2010s). */
    Ledm {
        override val capabilitiesPath = "/Scan/ScanCaps"

        override fun scanPage(http: HttpChannel, options: ScanOptions, sleep: (Long) -> Unit): ByteArray {
            val caps = http.exchange(get(capabilitiesPath)).text
            val (width, height) = scanArea(caps)

            if (!hasElement(http.exchange(get("/Scan/Status")).text, "ScannerState", "Idle")) {
                throw ScanException(ScanException.Reason.Busy, "Scanner not idle")
            }

            val color = if (options.color == ScanColor.Color) "Color" else "Gray"
            val settings = """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<ScanSettings xmlns="http://www.hp.com/schemas/imaging/con/cnx/scan/2008/08/19">""" +
                "<XResolution>${options.dpi}</XResolution><YResolution>${options.dpi}</YResolution>" +
                "<XStart>0</XStart><Width>$width</Width><YStart>0</YStart><Height>$height</Height>" +
                "<Format>Jpeg</Format><CompressionQFactor>15</CompressionQFactor>" +
                "<ColorSpace>$color</ColorSpace><BitDepth>8</BitDepth>" +
                "<InputSource>Platen</InputSource><InputSourceType>Platen</InputSourceType>" +
                "<GrayRendering>NTSC</GrayRendering>" +
                "<ToneMap><Gamma>0</Gamma><Brightness>1000</Brightness><Contrast>1000</Contrast>" +
                "<Highlite>0</Highlite><Shadow>0</Shadow></ToneMap>" +
                "<ContentType>Photo</ContentType></ScanSettings>" +
                // HPLIP ends the body with an empty chunk, counted in Content-Length.
                "\r\n0\r\n\r\n"
            val created = http.exchange(post("/Scan/Jobs", "*/*; charset=UTF-8", settings))
            val job = jobPath(created)

            // The page is ready to upload once the scanner has warmed up.
            var jobXml: String
            var polls = 0
            while (true) {
                jobXml = http.exchange(get(job)).text
                if (!hasElement(jobXml, "PreScanPage")) throw ScanException(ScanException.Reason.NoDocument, "No page in job: $jobXml")
                if (hasElement(jobXml, "PageState", "ReadyToUpload")) break
                if (hasElement(jobXml, "JobState", "Canceled") || hasElement(jobXml, "JobState", "Completed") ||
                    hasElement(jobXml, "PageState", "CanceledByDevice") || hasElement(jobXml, "PageState", "CanceledByClient")
                ) {
                    throw ScanException(ScanException.Reason.Failed, "Job ended without a page: $jobXml")
                }
                if (++polls > MAX_POLLS) throw ScanException(ScanException.Reason.Failed, "Page never ready")
                sleep(500)
            }

            val binaryUrl = element(jobXml, "BinaryURL")
                ?: throw ScanException(ScanException.Reason.Failed, "No BinaryURL: $jobXml")
            val image = http.exchange(get(binaryUrl))
            if (image.status != 200) throw ScanException(ScanException.Reason.Failed, "Image download: HTTP ${image.status}")
            return image.body
        }
    },

    /** Mopria/AirPrint scanning (eSCL), on recent multifunctions and scanners with IPP over USB. */
    Escl {
        override val capabilitiesPath = "/eSCL/ScannerCapabilities"

        override fun scanPage(http: HttpChannel, options: ScanOptions, sleep: (Long) -> Unit): ByteArray {
            val caps = http.exchange(get(capabilitiesPath)).text
            val (width, height) = scanArea(caps)

            if (!hasElement(http.exchange(get("/eSCL/ScannerStatus")).text, "State", "Idle")) {
                throw ScanException(ScanException.Reason.Busy, "Scanner not idle")
            }

            val colorMode = if (options.color == ScanColor.Color) "RGB24" else "Grayscale8"
            val settings = """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">""" +
                "<pwg:Version>2.0</pwg:Version>" +
                "<pwg:ScanRegions><pwg:ScanRegion>" +
                "<pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>" +
                "<pwg:XOffset>0</pwg:XOffset><pwg:YOffset>0</pwg:YOffset>" +
                "<pwg:Width>$width</pwg:Width><pwg:Height>$height</pwg:Height>" +
                "</pwg:ScanRegion></pwg:ScanRegions>" +
                "<pwg:InputSource>Platen</pwg:InputSource>" +
                "<scan:ColorMode>$colorMode</scan:ColorMode>" +
                "<scan:XResolution>${options.dpi}</scan:XResolution><scan:YResolution>${options.dpi}</scan:YResolution>" +
                "<pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>" +
                "<scan:DocumentFormatExt>image/jpeg</scan:DocumentFormatExt>" +
                "</scan:ScanSettings>"
            val created = http.exchange(post("/eSCL/ScanJobs", "text/xml", settings))
            val job = jobPath(created)

            // 503 while the page is being scanned.
            var polls = 0
            while (true) {
                val page = http.exchange(get("$job/NextDocument"))
                when (page.status) {
                    200 -> return page.body
                    503 -> if (++polls > MAX_POLLS) throw ScanException(ScanException.Reason.Failed, "Page never ready")
                    404 -> throw ScanException(ScanException.Reason.NoDocument, "No page in job")
                    else -> throw ScanException(ScanException.Reason.Failed, "Image download: HTTP ${page.status}")
                }
                sleep(1000)
            }
        }
    };

    /** A GET of this path answers 200 only on devices speaking the protocol. */
    abstract val capabilitiesPath: String

    /** [sleep] is a parameter so tests can run without waiting. */
    abstract fun scanPage(http: HttpChannel, options: ScanOptions, sleep: (Long) -> Unit = Thread::sleep): ByteArray

    companion object {
        private const val MAX_POLLS = 240
        // A4 in 1/300 inch, the unit of both protocols.
        private const val A4_WIDTH = 2480
        private const val A4_HEIGHT = 3508

        /**
         * The protocol [http] speaks, or null. eSCL is asked first: its request is plain HTTP,
         * which an LEDM device answers with a clean 404.
         */
        fun detect(http: HttpChannel): ScanProtocol? = listOf(Escl, Ledm).firstOrNull {
            http.exchange(get(it.capabilitiesPath)).status == 200
        }

        private fun get(path: String) = request("GET", path, null, null)

        private fun post(path: String, contentType: String, body: String) = request("POST", path, contentType, body)

        private fun request(method: String, path: String, contentType: String?, body: String?): ByteArray {
            val bytes = body?.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("User-Agent: MyPrinter\r\n")
                append("Accept: */*\r\n")
                if (bytes != null) {
                    append("Content-Type: $contentType\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                }
                append("\r\n")
            }
            return head.toByteArray(Charsets.ISO_8859_1) + (bytes ?: ByteArray(0))
        }

        /** The job path from a job creation response's Location, which may be an absolute URL. */
        private fun jobPath(created: HttpResponse): String {
            val location = created.header("location")
            if (created.status !in 200..299 || location == null) {
                throw ScanException(ScanException.Reason.Failed, "Job not created: HTTP ${created.status} ${created.text}")
            }
            return (if (location.startsWith("/")) location else URI(location).rawPath).removeSuffix("/")
        }

        /** Width and height in 1/300 inch: A4, cut to the glass's size given in the capabilities. */
        internal fun scanArea(caps: String): Pair<Int, Int> {
            val maxWidth = Regex("<(?:\\w+:)?Max(?:imum)?Width>\\s*(\\d+)").find(caps)?.groupValues?.get(1)?.toInt()
            val maxHeight = Regex("<(?:\\w+:)?Max(?:imum)?Height>\\s*(\\d+)").find(caps)?.groupValues?.get(1)?.toInt()
            return minOf(A4_WIDTH, maxWidth ?: A4_WIDTH) to minOf(A4_HEIGHT, maxHeight ?: A4_HEIGHT)
        }

        /** Whether [xml] has the element [name] (any namespace prefix), with [value] if given. */
        private fun hasElement(xml: String, name: String, value: String? = null): Boolean =
            if (value == null) Regex("<(?:\\w+:)?$name[\\s>]").containsMatchIn(xml)
            else Regex("<(?:\\w+:)?$name>\\s*${Regex.escape(value)}\\s*<").containsMatchIn(xml)

        private fun element(xml: String, name: String): String? =
            Regex("<(?:\\w+:)?$name>\\s*([^<]*?)\\s*<").find(xml)?.groupValues?.get(1)
    }
}
