package com.example.myprinter.print

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintAttributes.Margins
import android.print.PrintAttributes.MediaSize
import android.print.PrintAttributes.Resolution
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import com.example.myprinter.MyPrinterApplication
import com.example.myprinter.R
import com.example.myprinter.usb.PrintDocument
import com.example.myprinter.usb.PrintOptions
import com.example.myprinter.usb.PrintQuality
import java.io.File
import kotlin.concurrent.thread

/**
 * Makes the USB printer available in Android's "Print" menu (Gmail, Chrome, Drive...).
 * Apps hand over a PDF, printed with the same driver as the app's own screens.
 * The user has to enable the service once in the system printing settings.
 */
class UsbPrintService : PrintService() {
    private val printerManager get() = (application as MyPrinterApplication).printerManager
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Discovery()

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    // PrintJob methods must be called on the main thread, where this callback runs.
    override fun onPrintJobQueued(printJob: PrintJob) {
        if (!printerManager.ensureConnected()) {
            printJob.fail(getString(R.string.print_service_not_connected))
            return
        }
        val data = printJob.document.data
        if (data == null) {
            printJob.fail(getString(R.string.print_service_failed))
            return
        }
        val info = printJob.info
        val attributes = info.attributes
        val options = PrintOptions(
            copies = info.copies.coerceAtLeast(1),
            // Landscape jobs come as landscape PDF pages, rotated onto the portrait sheet.
            landscape = attributes.mediaSize?.isPortrait == false,
            grayscale = attributes.colorMode == PrintAttributes.COLOR_MODE_MONOCHROME,
            quality = PrintQuality.entries.firstOrNull { it.name == attributes.resolution?.id } ?: PrintQuality.Normal
        )
        printJob.start()

        thread(name = "print-job") {
            val printed = try {
                // The document arrives as a pipe; PdfRenderer needs a seekable file.
                val file = File.createTempFile("print-job", ".pdf", cacheDir)
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(data).use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    PrintDocument.openPdf(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                        printerManager.printDocument(it, options)
                    }
                } finally {
                    file.delete()
                }
            } catch (e: Exception) {
                false
            }
            mainHandler.post {
                when {
                    printJob.isCancelled -> Unit
                    printed -> printJob.complete()
                    else -> printJob.fail(getString(R.string.print_service_failed))
                }
            }
        }
    }

    /** Offers a single printer: the USB printer handled by the app, available when plugged in. */
    private inner class Discovery : PrinterDiscoverySession() {
        private val printerId: PrinterId = generatePrinterId("usb")

        override fun onStartPrinterDiscovery(priorityList: List<PrinterId>) {
            addPrinters(listOf(printerInfo(withCapabilities = false)))
        }

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            addPrinters(listOf(printerInfo(withCapabilities = true)))
        }

        override fun onStopPrinterDiscovery() = Unit
        override fun onValidatePrinters(printerIds: List<PrinterId>) = Unit
        override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit
        override fun onDestroy() = Unit

        private fun printerInfo(withCapabilities: Boolean): PrinterInfo {
            val status = if (printerManager.isPrinterPlugged()) PrinterInfo.STATUS_IDLE else PrinterInfo.STATUS_UNAVAILABLE
            val builder = PrinterInfo.Builder(printerId, getString(R.string.print_service_printer_name), status)
            if (withCapabilities) {
                builder.setCapabilities(
                    PrinterCapabilitiesInfo.Builder(printerId)
                        // The driver prints A4 only.
                        .addMediaSize(MediaSize.ISO_A4, true)
                        .addResolution(Resolution(PrintQuality.Draft.name, getString(R.string.quality_draft), 300, 300), false)
                        .addResolution(Resolution(PrintQuality.Normal.name, getString(R.string.quality_normal), 600, 300), true)
                        .addResolution(Resolution(PrintQuality.Best.name, getString(R.string.quality_best), 600, 600), false)
                        .setColorModes(
                            PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME,
                            PrintAttributes.COLOR_MODE_COLOR
                        )
                        // Pages are scaled into the printable area by the driver.
                        .setMinMargins(Margins.NO_MARGINS)
                        .build()
                )
            }
            return builder.build()
        }
    }
}
