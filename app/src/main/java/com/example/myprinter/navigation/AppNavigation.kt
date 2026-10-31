package com.example.myprinter.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.myprinter.ui.screens.FileSelectionScreen
import com.example.myprinter.ui.screens.PrinterConnectionScreen
import com.example.myprinter.ui.screens.PrintPreviewScreen
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
}

@Composable
fun AppNavigation() {
    val backStack = rememberNavBackStack(Route.FileSelection)
    val printerViewModel: PrinterViewModel = viewModel()
    val printJobViewModel: PrintJobViewModel = viewModel()

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
                else -> NavEntry(key) { }
            }
        }
    )
}
