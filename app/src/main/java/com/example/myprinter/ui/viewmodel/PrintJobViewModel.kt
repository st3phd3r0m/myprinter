package com.example.myprinter.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class PrintJobStatus {
    Pending, Printing, Completed, Error
}

data class PrintJob(
    val id: String,
    val fileName: String,
    val status: PrintJobStatus,
    val progress: Float,
    val timestamp: Long = System.currentTimeMillis()
)

data class PrintSettings(
    val copies: Int = 1,
    val orientation: String = "Portrait",
    val colorMode: String = "Color"
)

class PrintJobViewModel : ViewModel() {
    private val _printJobs = MutableStateFlow<List<PrintJob>>(emptyList())
    val printJobs: StateFlow<List<PrintJob>> = _printJobs.asStateFlow()

    private val _currentSettings = MutableStateFlow(PrintSettings())
    val currentSettings: StateFlow<PrintSettings> = _currentSettings.asStateFlow()

    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri: StateFlow<Uri?> = _selectedUri.asStateFlow()

    fun selectFile(uri: Uri?) {
        _selectedUri.value = uri
    }

    fun updateSettings(settings: PrintSettings) {
        _currentSettings.value = settings
    }

    fun startPrintJob(fileName: String, onActualPrint: suspend () -> Boolean) {
        val jobId = System.currentTimeMillis().toString()
        val newJob = PrintJob(jobId, fileName, PrintJobStatus.Pending, 0f)
        _printJobs.value = listOf(newJob) + _printJobs.value

        viewModelScope.launch {
            _printJobs.update { jobs ->
                jobs.map { if (it.id == jobId) it.copy(status = PrintJobStatus.Printing, progress = 0.1f) else it }
            }
            
            val success = onActualPrint()
            
            _printJobs.update { jobs ->
                jobs.map { if (it.id == jobId) {
                    if (success) it.copy(status = PrintJobStatus.Completed, progress = 1f)
                    else it.copy(status = PrintJobStatus.Error, progress = 0f)
                } else it }
            }
        }
    }

    private suspend fun simulatePrintJob(jobId: String) {
        _printJobs.update { jobs ->
            jobs.map { if (it.id == jobId) it.copy(status = PrintJobStatus.Printing) else it }
        }

        for (i in 1..10) {
            delay(500)
            _printJobs.update { jobs ->
                jobs.map { if (it.id == jobId) it.copy(progress = i * 0.1f) else it }
            }
        }

        _printJobs.update { jobs ->
            jobs.map { if (it.id == jobId) it.copy(status = PrintJobStatus.Completed, progress = 1f) else it }
        }
    }
}
