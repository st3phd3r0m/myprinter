package com.example.myprinter.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myprinter.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

sealed class PrinterConnectionState {
    data object Idle : PrinterConnectionState()
    data object RequestingPermission : PrinterConnectionState()
    data class Connected(val deviceName: String, val model: String) : PrinterConnectionState()
    data class Error(val message: String) : PrinterConnectionState()
    data object Disconnected : PrinterConnectionState()
}

class UsbPrinterManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    
    private val _connectionState = MutableStateFlow<PrinterConnectionState>(PrinterConnectionState.Idle)
    val connectionState: StateFlow<PrinterConnectionState> = _connectionState.asStateFlow()

    private var usbDevice: UsbDevice? = null
    private var usbInterface: UsbInterface? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private val ACTION_USB_PERMISSION = "com.example.myprinter.USB_PERMISSION"

    private val logManager = LogManager(context)

    // The last printer connected to, reconnected automatically (see connectToSavedPrinter).
    private val prefs = context.getSharedPreferences("printer", Context.MODE_PRIVATE)

    private fun addLog(msg: String) {
        Log.d("UsbPrinterManager", msg)
        logManager.log(msg)
    }

    fun exportLogsToDownloads(): String {
        return logManager.exportToDownloads()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            addLog("Broadcast: $action")
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    
                    val permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    addLog("Permission granted extra: $permissionGranted")

                    if (permissionGranted) {
                        device?.let { connectToDevice(it) } ?: usbDevice?.let { connectToDevice(it) }
                    } else {
                        addLog("Permission denied by user")
                        _connectionState.value = PrinterConnectionState.Error(context.getString(R.string.error_permission_denied))
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                // Without permission, Android's own "open MyPrinter?" prompt handles it (see MainActivity).
                connectToSavedPrinter(askPermission = false)
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (device == usbDevice) {
                    addLog("Printer detached")
                    disconnect()
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                context,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    fun discoverPrinters(): List<UsbDevice> {
        val deviceList = usbManager.deviceList
        addLog("Scanning USB: Found ${deviceList.size} devices")
        return deviceList.values.filter { isPrinter(it) }.also {
            addLog("Discovered ${it.size} printers")
        }
    }

    private fun isPrinter(device: UsbDevice): Boolean {
        // HP Deskjet 1510 VID: 0x03F0, PIDs: 0xC111, 0xC202
        if (device.vendorId == 0x03F0 && (device.productId == 0xC111 || device.productId == 0xC202)) {
            return true
        }
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                return true
            }
        }
        return false
    }

    /**
     * Connects to the last printer used, if it is plugged in. Called at startup and when a USB
     * device is attached; does nothing while a connection or permission request is in progress.
     * @param askPermission whether to show Android's USB permission dialog if access is not granted yet.
     */
    fun connectToSavedPrinter(askPermission: Boolean = true) {
        if (usbConnection != null || _connectionState.value is PrinterConnectionState.RequestingPermission) return
        val vendorId = prefs.getInt(KEY_VENDOR_ID, -1)
        val productId = prefs.getInt(KEY_PRODUCT_ID, -1)
        if (vendorId == -1) return
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == vendorId && it.productId == productId }
        if (device == null) {
            addLog("Saved printer not plugged in")
            return
        }
        if (!askPermission && !usbManager.hasPermission(device)) return
        addLog("Reconnecting to saved printer")
        requestPermission(device)
    }

    /**
     * For callers without a printer screen (the print service): connects to the saved printer,
     * or else to the only printer plugged in. Returns false if USB permission still has to be
     * granted; the request is then shown and the caller can retry.
     */
    fun ensureConnected(): Boolean {
        if (usbConnection != null) return true
        connectToSavedPrinter()
        if (usbConnection == null && _connectionState.value !is PrinterConnectionState.RequestingPermission) {
            discoverPrinters().singleOrNull()?.let { requestPermission(it) }
        }
        return usbConnection != null
    }

    fun isPrinterPlugged(): Boolean = usbConnection != null || discoverPrinters().isNotEmpty()

    fun requestPermission(device: UsbDevice) {
        if (usbConnection != null && device == usbDevice) {
            addLog("Already connected to ${device.deviceName}")
            return
        }
        if (usbManager.hasPermission(device)) {
            addLog("Permission already held, connecting...")
            connectToDevice(device)
        } else {
            addLog("Requesting permission for ${device.deviceName}")
            usbDevice = device 
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                `package` = context.packageName
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            _connectionState.value = PrinterConnectionState.RequestingPermission
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        addLog("Connecting to ${device.deviceName} (VID: 0x${String.format("%04X", device.vendorId)})")
        usbDevice = device
        
        var foundInterface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                foundInterface = iface
                addLog("Printer Interface found at index $i")
                break
            }
        }

        if (foundInterface == null) {
            addLog("Error: No Printer Class (7) interface found")
            _connectionState.value = PrinterConnectionState.Error(context.getString(R.string.error_no_interface))
            return
        }
        usbInterface = foundInterface

        // Find bulk endpoints
        endpointIn = null
        endpointOut = null
        for (i in 0 until foundInterface.endpointCount) {
            val ep = foundInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    endpointIn = ep
                    addLog("Bulk IN: 0x${String.format("%02X", ep.address)}")
                } else {
                    endpointOut = ep
                    addLog("Bulk OUT: 0x${String.format("%02X", ep.address)}")
                }
            }
        }

        usbConnection = usbManager.openDevice(device)
        if (usbConnection != null) {
            if (usbConnection!!.claimInterface(foundInterface, true)) {
                addLog("Interface claimed. Connected.")
                prefs.edit()
                    .putInt(KEY_VENDOR_ID, device.vendorId)
                    .putInt(KEY_PRODUCT_ID, device.productId)
                    .apply()
                val model = getModelName(device)
                _connectionState.value = PrinterConnectionState.Connected(device.deviceName, model)
            } else {
                addLog("Error: Could not claim interface")
                usbConnection?.close()
                usbConnection = null
                _connectionState.value = PrinterConnectionState.Error(context.getString(R.string.error_claim))
            }
        } else {
            addLog("Error: Open device failed")
            _connectionState.value = PrinterConnectionState.Error(context.getString(R.string.error_open))
        }
    }

    private fun getModelName(device: UsbDevice): String {
        val conn = usbConnection ?: return device.productName ?: "Unknown Printer"
        val buffer = ByteArray(512)
        val ifaceId = usbInterface?.id ?: 0
        
        // Control Transfer: GET_DEVICE_ID (0xA1, 0, 0, ifaceId)
        val len = conn.controlTransfer(0xA1, 0, 0, ifaceId, buffer, buffer.size, 2000)

        if (len > 2) {
            val raw = String(buffer, 2, len - 2)
            addLog("IEEE 1284: $raw")
            val mdlIndex = raw.indexOf("MDL:")
            if (mdlIndex != -1) {
                val end = raw.indexOf(";", mdlIndex)
                return if (end != -1) raw.substring(mdlIndex + 4, end) else raw.substring(mdlIndex + 4)
            }
            return raw
        }
        return device.productName ?: "Generic Printer"
    }

    fun checkStatus(): String {
        val conn = usbConnection ?: return context.getString(R.string.status_not_connected)
        val epIn = endpointIn ?: return context.getString(R.string.status_endpoint_missing)
        val epOut = endpointOut ?: return context.getString(R.string.status_endpoint_missing)

        // Try PJL Status
        val pjlQuery = "\u001B%-12345X@PJL INFO STATUS\r\n"
        val outLen = conn.bulkTransfer(epOut, pjlQuery.toByteArray(), pjlQuery.length, 2000)
        
        if (outLen >= 0) {
            val buffer = ByteArray(1024)
            val inLen = conn.bulkTransfer(epIn, buffer, buffer.size, 3000)
            if (inLen > 0) {
                val resp = String(buffer, 0, inLen).trim()
                addLog("Status: $resp")
                return if (resp.contains("READY", ignoreCase = true)) context.getString(R.string.status_ready) else context.getString(R.string.status_online)
            }
        }
        
        addLog("Status check timed out (no PJL status from printer)")
        return context.getString(R.string.status_no_response)
    }

    /** Prints an image or a PDF, rendering and sending one page at a time. */
    fun printDocument(uri: Uri, options: PrintOptions): Boolean {
        if (usbConnection == null) {
            addLog("Cannot print: no printer connected")
            return false
        }
        return try {
            PrintDocument.open(context, uri).use { printDocument(it, options) }
        } catch (e: Exception) {
            addLog("Cannot print document: ${e.message}")
            false
        }
    }

    /** Prints an already opened document; the caller closes it. */
    fun printDocument(document: PrintDocument, options: PrintOptions): Boolean {
        if (usbConnection == null) {
            addLog("Cannot print: no printer connected")
            return false
        }
        return try {
            addLog("Printing ${document.pageCount} page(s), $options")
            send { out ->
                Pcl3GuiDriver().writeJob(out, document.pageCount, options) { index ->
                    addLog("Rendering page ${index + 1}/${document.pageCount}")
                    document.renderPage(index)
                }
            }
        } catch (e: Exception) {
            addLog("Cannot print document: ${e.message}")
            false
        }
    }

    /** Sends ready-made printer data (e.g. a PCL file produced by hpcups on a PC) as is. */
    fun printRaw(data: ByteArray): Boolean = send { it.write(data) }

    private fun send(write: (OutputStream) -> Unit): Boolean {
        val conn = usbConnection ?: return false
        val epOut = endpointOut ?: return false
        val usbOut = UsbBulkOutputStream(conn, epOut)
        return try {
            synchronized(conn) {
                BufferedOutputStream(usbOut, CHUNK_SIZE).use(write)
            }
            addLog("Sent ${usbOut.sent} bytes.")
            true
        } catch (e: IOException) {
            addLog("Error: ${e.message}")
            false
        }
    }

    /** USB bulk transfers are flow-controlled by the printer, so no throttling is needed. */
    private class UsbBulkOutputStream(
        private val conn: UsbDeviceConnection,
        private val endpoint: UsbEndpoint
    ) : OutputStream() {
        var sent = 0L
            private set

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var done = 0
            while (done < len) {
                val result = conn.bulkTransfer(endpoint, b, off + done, minOf(CHUNK_SIZE, len - done), TRANSFER_TIMEOUT_MS)
                if (result < 0) throw IOException("Transfer failed at $sent bytes")
                done += result
                sent += result
            }
        }
    }

    fun disconnect() {
        addLog("Disconnecting...")
        usbConnection?.apply {
            usbInterface?.let { releaseInterface(it) }
            close()
        }
        usbConnection = null
        usbInterface = null
        usbDevice = null
        endpointIn = null
        endpointOut = null
        _connectionState.value = PrinterConnectionState.Disconnected
    }


    private companion object {
        const val KEY_VENDOR_ID = "vendor_id"
        const val KEY_PRODUCT_ID = "product_id"
        const val CHUNK_SIZE = 16384
        const val TRANSFER_TIMEOUT_MS = 30000
    }
}
