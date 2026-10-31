package com.example.myprinter.usb

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.myprinter.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Handles persistent logging and exporting logs to the public Downloads folder.
 */
class LogManager(private val context: Context) {
    private val logFile = File(context.filesDir, "myprinter_debug_logs.txt")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /**
     * Appends a message to the internal log file.
     */
    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val logEntry = "[$timestamp] $message\n"
        try {
            FileOutputStream(logFile, true).use { output ->
                output.write(logEntry.toByteArray())
            }
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to write log", e)
        }
    }

    /**
     * Copies the internal log file to the public Downloads folder.
     */
    fun exportToDownloads(): String {
        if (!logFile.exists()) return context.getString(R.string.logs_none)

        val fileName = "MyPrinter_Logs_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10+
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { output ->
                        logFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    context.getString(R.string.logs_saved, "Downloads/$fileName")
                } ?: context.getString(R.string.logs_create_failed)
            } else {
                // Legacy way for older versions
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetFile = File(downloadsDir, fileName)
                logFile.inputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                context.getString(R.string.logs_saved, targetFile.absolutePath)
            }
        } catch (e: Exception) {
            Log.e("LogManager", "Export failed", e)
            context.getString(R.string.logs_error, e.message)
        }
    }

    fun clearLogs() {
        if (logFile.exists()) logFile.delete()
    }
}
