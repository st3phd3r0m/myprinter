package com.example.myprinter.storage

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Where scans are saved: Documents/MyPrinter when Android lets the app create it, else
 * Download/MyPrinter. Both are shared folders that file managers show.
 */
object ScanStorage {
    private const val APP_FOLDER = "MyPrinter"

    /**
     * Creates the folder if it can and returns its parent standard directory
     * ([Environment.DIRECTORY_DOCUMENTS] or [Environment.DIRECTORY_DOWNLOADS]).
     * Called at startup, so the folder is there before the first scan, and before each save.
     */
    fun prepareFolder(context: Context): String = when {
        // A folder can't be created without a file in it: MediaStore creates it on the first save.
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> Environment.DIRECTORY_DOCUMENTS
        // Asked for when saving, not at startup.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasLegacyWritePermission(context) -> Environment.DIRECTORY_DOWNLOADS
        // From Android 11, and with the permission before 10, folders in shared storage can be created directly.
        createIn(Environment.DIRECTORY_DOCUMENTS) -> Environment.DIRECTORY_DOCUMENTS
        else -> {
            createIn(Environment.DIRECTORY_DOWNLOADS)
            Environment.DIRECTORY_DOWNLOADS
        }
    }

    /** Below Android 10, writing to shared storage needs [Manifest.permission.WRITE_EXTERNAL_STORAGE]. */
    fun needsPermission(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasLegacyWritePermission(context)

    /** The folder as shown to the user, e.g. "Documents/MyPrinter". */
    fun folderLabel(directory: String) = "$directory/$APP_FOLDER"

    /** A file to save: [write] produces its content. */
    class OutputFile(val name: String, val mimeType: String, val write: (OutputStream) -> Unit)

    /**
     * Writes [files] into the app folder, created first if needed, and returns its parent
     * standard directory. If Android refuses the files in Documents, they go to Download instead.
     */
    fun save(context: Context, files: List<OutputFile>): String {
        val directory = prepareFolder(context)
        try {
            files.forEach { write(context, directory, it) }
            return directory
        } catch (e: Exception) {
            if (directory != Environment.DIRECTORY_DOCUMENTS) throw e
        }
        createIn(Environment.DIRECTORY_DOWNLOADS)
        files.forEach { write(context, Environment.DIRECTORY_DOWNLOADS, it) }
        return Environment.DIRECTORY_DOWNLOADS
    }

    private fun write(context: Context, directory: String, file: OutputFile) {
        val name = file.name
        val mimeType = file.mimeType
        val write = file.write
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore needs no permission for files the app adds; MediaStore.Downloads takes
            // any type, the Files collection is the one for Documents.
            val resolver = context.contentResolver
            val collection = if (directory == Environment.DIRECTORY_DOWNLOADS) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folderLabel(directory))
                // Hidden from other apps until complete.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("Cannot create $name")
            try {
                resolver.openOutputStream(uri)?.buffered()?.use(write) ?: throw IOException("Cannot write $name")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val folder = folder(directory)
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create $folder")
            val file = File(folder, name)
            try {
                file.outputStream().buffered().use(write)
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mimeType), null)
        }
    }

    /** Whether the app folder exists in [directory], creating it if needed; false if Android refuses. */
    private fun createIn(directory: String): Boolean {
        val parent = publicDirectory(directory)
        val folder = File(parent, APP_FOLDER)
        return parent.isDirectory && (folder.isDirectory || folder.mkdir())
    }

    private fun folder(directory: String) = File(publicDirectory(directory), APP_FOLDER)

    // Used for its path; from Android 10 the writing goes through MediaStore.
    @Suppress("DEPRECATION")
    private fun publicDirectory(directory: String): File = Environment.getExternalStoragePublicDirectory(directory)

    private fun hasLegacyWritePermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}
