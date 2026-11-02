package com.example.myprinter.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.myprinter.ui.screens.FileSelectionScreen
import com.example.myprinter.ui.screens.PrinterConnectionScreen
import com.example.myprinter.ui.screens.PrintPreviewScreen
import com.example.myprinter.ui.screens.ScanScreen
import com.example.myprinter.ui.viewmodel.ScanViewModel
import kotlinx.serialization.Serializable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myprinter.ui.viewmodel.PrinterViewModel
import com.example.myprinter.ui.viewmodel.PrintJobViewModel

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object FileSelection : Route
    @Serializable
    data object PrinterConnection : Route
    @Serializable
    data object PrintPreview : Route
    @Serializable
    data object Scan : Route
}

@Composable
fun AppNavigation() {
    val backStack = rememberNavBackStack(Route.FileSelection)
    val printerViewModel: PrinterViewModel = viewModel()
    val printJobViewModel: PrintJobViewModel = viewModel()
    val scanViewModel: ScanViewModel = viewModel()

    // A file shared from another app goes straight to the print screen, on top of the file screen.
    val openPrintScreen by printJobViewModel.openPrintScreen.collectAsState()
    LaunchedEffect(openPrintScreen) {
        if (openPrintScreen) {
            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            backStack.add(Route.PrintPreview)
            printJobViewModel.onPrintScreenOpened()
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = { key ->
            when (key) {
                is Route.FileSelection -> {
                    NavEntry(key) {
                        FileSelectionScreen(
                            viewModel = printJobViewModel,
                            onNavigateToPrinter = { backStack.add(Route.PrinterConnection) },
                            onNavigateToScan = { backStack.add(Route.Scan) },
                            onNavigateToPrintPreview = { uri ->
                                printJobViewModel.selectFile(uri)
                                backStack.add(Route.PrintPreview)
                            }
                        )
                    }
                }
                is Route.PrinterConnection -> {
                    NavEntry(key) {
                        PrinterConnectionScreen(
                            viewModel = printerViewModel,
                            onBack = { backStack.removeLastOrNull() }
                        )
                    }
                }
                is Route.PrintPreview -> {
                    NavEntry(key) {
                        PrintPreviewScreen(
                            viewModel = printJobViewModel,
                            printerViewModel = printerViewModel,
                            onBack = { backStack.removeLastOrNull() }
                        )
                    }
                }
                is Route.Scan -> {
                    NavEntry(key) {
                        ScanScreen(
                            viewModel = scanViewModel,
                            onBack = { backStack.removeLastOrNull() },
                            onNavigateToPrinter = { backStack.add(Route.PrinterConnection) },
                            onPrint = { uri ->
                                printJobViewModel.selectFile(uri)
                                backStack.add(Route.PrintPreview)
                            }
                        )
                    }
                }
                else -> NavEntry(key) { }
            }
        }
    )
}
