package com.example.myprinter.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myprinter.MyPrinterApplication
import com.example.myprinter.R
import com.example.myprinter.storage.ScanStorage
import com.example.myprinter.storage.freeName
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

/** How the pages are saved and shared: one PDF, or one JPEG per page. Printing always uses the PDF. */
enum class ScanFormat { Pdf, Jpeg }

/** The pages scanned so far, kept as JPEG files in the cache until saved, shared or printed. */
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

    private val _format = MutableStateFlow(ScanFormat.Pdf)
    val format: StateFlow<ScanFormat> = _format.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun updateOptions(options: ScanOptions) {
        _options.value = options
    }

    fun updateFormat(format: ScanFormat) {
        _format.value = format
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

    /** The pages as one PDF, for sharing or printing; [dictated] is the file name the user said, if any. */
    suspend fun pdfUri(dictated: String? = null): Uri = sharedFiles(outputFiles(ScanFormat.Pdf, dictated, _pages.value)).single()

    /** The page images, for sharing. */
    suspend fun jpegUris(dictated: String?): List<Uri> = sharedFiles(outputFiles(ScanFormat.Jpeg, dictated, _pages.value))

    /** Writes [files] to the cache folder shared through the FileProvider, replacing the previous ones. */
    private suspend fun sharedFiles(files: List<ScanStorage.OutputFile>): List<Uri> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val shared = File(dir, "shared")
        shared.deleteRecursively()
        shared.mkdirs()
        files.map { file ->
            val target = File(shared, freeName(file.baseName, file.extension) { File(shared, it).exists() })
            target.outputStream().buffered().use(file.write)
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", target)
        }
    }

    /**
     * Without a dictated name: Scan_<date>_<time>. A PDF takes the dictated name followed by the
     * date and time; JPEGs take it alone, numbered _1, _2... where the name is already taken.
     */
    private fun outputFiles(format: ScanFormat, dictated: String?, pages: List<ScannedPage>): List<ScanStorage.OutputFile> =
        when (format) {
            ScanFormat.Pdf -> listOf(
                ScanStorage.OutputFile(dictated?.let { "${it}_${timestamp()}" } ?: defaultName(), "pdf", "application/pdf") {
                    JpegPdf.write(it, pages)
                }
            )
            ScanFormat.Jpeg -> pages.map { page ->
                ScanStorage.OutputFile(dictated ?: defaultName(), "jpg", "image/jpeg") { out -> page.file.inputStream().use { it.copyTo(out) } }
            }
        }

    /**
     * Saves the pages in the chosen format to Documents/MyPrinter or Download/MyPrinter
     * (see [ScanStorage.save]); [dictated] is the file name the user said, if any.
     */
    fun save(dictated: String?) {
        val app = getApplication<Application>()
        val pages = _pages.value
        val format = _format.value
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    ScanStorage.save(app, outputFiles(format, dictated, pages))
                } catch (e: Exception) {
                    null
                }
            }
            showMessage(when {
                saved == null -> app.getString(R.string.scan_save_failed)
                format == ScanFormat.Pdf -> app.getString(R.string.scan_pdf_saved, saved.names.single(), ScanStorage.folderLabel(saved.directory))
                else -> app.resources.getQuantityString(R.plurals.scan_jpeg_saved, pages.size, pages.size, ScanStorage.folderLabel(saved.directory))
            })
        }
    }

    private fun defaultName() = "Scan_${timestamp()}"

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())

    private fun showMessage(text: String) {
        _message.value = text
        viewModelScope.launch {
            delay(3000)
            if (_message.value == text) _message.value = null
        }
    }
}
