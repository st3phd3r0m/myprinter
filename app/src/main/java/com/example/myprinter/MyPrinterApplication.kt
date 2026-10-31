package com.example.myprinter

import android.app.Application
import com.example.myprinter.usb.UsbPrinterManager

class MyPrinterApplication : Application() {
    /**
     * The single owner of the USB printer connection, shared by the screens and the print
     * service (which can run without any screen open). Lives as long as the process.
     */
    val printerManager by lazy { UsbPrinterManager(this) }
}
