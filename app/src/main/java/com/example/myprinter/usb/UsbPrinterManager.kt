package com.example.myprinter.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
    val debugLogs: StateFlow<List<String>> = _debugLogs.asStateFlow()

    private var usbDevice: UsbDevice? = null
    private var usbInterface: UsbInterface? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private val ACTION_USB_PERMISSION = "com.example.myprinter.USB_PERMISSION"

    private val logManager = LogManager(context)

    private fun addLog(msg: String) {
        Log.d("UsbPrinterManager", msg)
        logManager.log(msg)
        _debugLogs.value = (listOf(msg) + _debugLogs.value).take(15)
    }

    fun clearLogs() {
        _debugLogs.value = emptyList()
        logManager.clearLogs()
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
                        _connectionState.value = PrinterConnectionState.Error("Permission denied")
                    }
                }
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

    fun requestPermission(device: UsbDevice) {
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
            _connectionState.value = PrinterConnectionState.Error("No printer interface")
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
                val model = getModelName(device)
                _connectionState.value = PrinterConnectionState.Connected(device.deviceName, model)
            } else {
                addLog("Error: Could not claim interface")
                usbConnection?.close()
                usbConnection = null
                _connectionState.value = PrinterConnectionState.Error("Claim failed")
            }
        } else {
            addLog("Error: Open device failed")
            _connectionState.value = PrinterConnectionState.Error("Open failed")
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
        val conn = usbConnection ?: return "Not connected"
        val epIn = endpointIn ?: return "IN ep missing"
        val epOut = endpointOut ?: return "OUT ep missing"

        // Try PJL Status
        val pjlQuery = "\u001B%-12345X@PJL INFO STATUS\r\n"
        val outLen = conn.bulkTransfer(epOut, pjlQuery.toByteArray(), pjlQuery.length, 2000)
        
        if (outLen >= 0) {
            val buffer = ByteArray(1024)
            val inLen = conn.bulkTransfer(epIn, buffer, buffer.size, 3000)
            if (inLen > 0) {
                val resp = String(buffer, 0, inLen).trim()
                addLog("Status: $resp")
                return if (resp.contains("READY", ignoreCase = true)) "Ready" else "Online"
            }
        }
        
        addLog("Status check timed out (no PJL status from printer)")
        return "No status response"
    }

    fun printBitmap(bitmap: Bitmap): Boolean {
        addLog("Processing Bitmap ${bitmap.width}x${bitmap.height}")
        return printRaw(Pcl3GuiDriver().generatePclData(bitmap))
    }

    /**
     * Sends ready-made printer data (e.g. a PCL file produced by hpcups on a PC) as is.
     * USB bulk transfers are flow-controlled by the printer, so no throttling is needed.
     */
    fun printRaw(data: ByteArray): Boolean {
        val conn = usbConnection ?: return false
        val epOut = endpointOut ?: return false
        addLog("Sending ${data.size} bytes...")

        synchronized(conn) {
            var sent = 0
            while (sent < data.size) {
                val len = minOf(CHUNK_SIZE, data.size - sent)
                val result = conn.bulkTransfer(epOut, data, sent, len, TRANSFER_TIMEOUT_MS)
                if (result < 0) {
                    addLog("Error: Transfer failed at $sent bytes")
                    return false
                }
                sent += result
            }
        }
        addLog("Sent ${data.size} bytes.")
        return true
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

    fun cleanup() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {}
        disconnect()
    }

    private companion object {
        const val CHUNK_SIZE = 16384
        const val TRANSFER_TIMEOUT_MS = 30000
    }
}
