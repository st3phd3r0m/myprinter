package com.example.myprinter.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myprinter.MyPrinterApplication
import com.example.myprinter.R
import com.example.myprinter.usb.JpegPdf
import com.example.myprinter.usb.PrinterConnectionState
import com.example.myprinter.usb.ScanException
import com.example.myprinter.usb.ScanOptions
import com.example.myprinter.usb.ScannedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The pages scanned so far, kept as JPEG files in the cache until saved, shared or printed as a PDF. */
class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val printerManager = (application as MyPrinterApplication).printerManager

    val connectionState: StateFlow<PrinterConnectionState> = printerManager.connectionState

    // Shared through the FileProvider (res/xml/file_paths.xml). Emptied at start: pages of a
    // previous run are no longer listed.
    private val dir = File(application.cacheDir, "scans").apply {
        deleteRecursively()
        mkdirs()
    }

    private val _pages = MutableStateFlow<List<ScannedPage>>(emptyList())
    val pages: StateFlow<List<ScannedPage>> = _pages.asStateFlow()

    private val _options = MutableStateFlow(ScanOptions())
    val options: StateFlow<ScanOptions> = _options.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun updateOptions(options: ScanOptions) {
        _options.value = options
    }

    fun scanPage() {
        if (_scanning.value) return
        _scanning.value = true
        val app = getApplication<Application>()
        val options = _options.value
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    val jpeg = printerManager.scan(options)
                    val file = File(dir, "page_${System.currentTimeMillis()}.jpg")
                    file.writeBytes(jpeg)
                    ScannedPage(file, options.dpi)
                }
                _pages.update { it + page }
            } catch (e: ScanException) {
                showMessage(app.getString(when (e.reason) {
                    ScanException.Reason.Busy -> R.string.scan_error_busy
                    ScanException.Reason.NoDocument -> R.string.scan_error_no_document
                    ScanException.Reason.Failed -> R.string.scan_error_failed
                }))
            } catch (e: IOException) {
                showMessage(app.getString(R.string.scan_error_failed))
            } finally {
                _scanning.value = false
            }
        }
    }

    fun removePage(page: ScannedPage) {
        page.file.delete()
        _pages.update { it - page }
    }

    /** The pages as one PDF, for sharing or printing. */
    suspend fun pdfUri(): Uri = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        dir.listFiles { file -> file.extension == "pdf" }?.forEach { it.delete() }
        // The name shows in the app the PDF is shared to.
        val pdf = File(dir, defaultPdfName())
        pdf.outputStream().buffered().use { JpegPdf.write(it, _pages.value) }
        FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", pdf)
    }

    /** Writes the PDF to [target], a document the user created through the system file picker. */
    fun savePdf(target: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    app.contentResolver.openOutputStream(target)?.buffered()?.use { JpegPdf.write(it, _pages.value) } != null
                } catch (e: IOException) {
                    false
                }
            }
            showMessage(app.getString(if (saved) R.string.scan_saved else R.string.scan_save_failed))
        }
    }

    fun defaultPdfName(): String =
        "Scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}.pdf"

    private fun showMessage(text: String) {
        _message.value = text
        viewModelScope.launch {
            delay(3000)
            if (_message.value == text) _message.value = null
        }
    }
}
