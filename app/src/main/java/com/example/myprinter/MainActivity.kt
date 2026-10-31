package com.example.myprinter

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.myprinter.navigation.AppNavigation
import com.example.myprinter.ui.theme.MyPrinterTheme
import com.example.myprinter.ui.viewmodel.PrintJobViewModel
import com.example.myprinter.ui.viewmodel.PrinterViewModel

class MainActivity : ComponentActivity() {
    // Same instance as the one AppNavigation gets from viewModel(): both use this activity's store.
    private val printerViewModel: PrinterViewModel by viewModels()
    private val printJobViewModel: PrintJobViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyPrinterTheme {
                AppNavigation()
            }
        }
        // On recreation (e.g. rotation) the intent was already handled.
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleUsbIntent(intent)
            // Shared or opened from another app; the read grant on the Uri comes with the intent.
            Intent.ACTION_SEND -> intentStream(intent)?.let { printJobViewModel.printFromOtherApp(it) }
            Intent.ACTION_VIEW -> intent.data?.let { printJobViewModel.printFromOtherApp(it) }
        }
    }

    private fun intentStream(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    /** Opened by Android because the printer was plugged in: USB access is already granted. */
    private fun handleUsbIntent(intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        device?.let { printerViewModel.connectToPrinter(it) }
    }
}
