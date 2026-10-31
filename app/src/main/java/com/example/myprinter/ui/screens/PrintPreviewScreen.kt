package com.example.myprinter.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myprinter.ui.viewmodel.PrintJob
import com.example.myprinter.ui.viewmodel.PrintJobStatus
import com.example.myprinter.ui.viewmodel.PrintJobViewModel
import com.example.myprinter.ui.viewmodel.PrintSettings
import com.example.myprinter.ui.viewmodel.PrinterViewModel
import com.example.myprinter.usb.PrintDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PrintPreviewScreen(
    viewModel: PrintJobViewModel,
    printerViewModel: PrinterViewModel,
    onBack: () -> Unit
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>()
    val settings by viewModel.currentSettings.collectAsState()
    val printJobs by viewModel.printJobs.collectAsState()
    val selectedUri by viewModel.selectedUri.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Print Preview & Queue") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        ListDetailPaneScaffold(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            listPane = {
                // On phones only one pane is shown: put the preview above the settings.
                val detailHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Hidden
                SettingsAndQueuePane(
                    settings = settings,
                    printJobs = printJobs,
                    preview = if (detailHidden) {
                        { PreviewPane(uri = selectedUri, settings = settings, modifier = Modifier.fillMaxWidth().height(420.dp)) }
                    } else {
                        null
                    },
                    onSettingsChange = { viewModel.updateSettings(it) },
                    onPrint = { 
                        selectedUri?.let { uri ->
                            viewModel.startPrintJob(uri.lastPathSegment ?: "Document") {
                                printerViewModel.printDocument(uri, settings)
                            }
                        }
                    }
                )
            },
            detailPane = {
                PreviewPane(uri = selectedUri, settings = settings)
            }
        )
    }
}

@Composable
fun SettingsAndQueuePane(
    settings: PrintSettings,
    printJobs: List<PrintJob>,
    preview: (@Composable () -> Unit)?,
    onSettingsChange: (PrintSettings) -> Unit,
    onPrint: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        if (preview != null) {
            item { preview() }
        }
        item {
            Text("Print Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            PrintSettingsCard(settings, onSettingsChange)
            Spacer(modifier = Modifier.height(24.dp))
            ElevatedButton(
                onClick = onPrint,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Rounded.Print, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Print Now", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        item {
            Text("Print Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        if (printJobs.isEmpty()) {
            item {
                Text("No recent jobs", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(printJobs) { job ->
                PrintJobItem(job)
            }
        }
    }
}

@Composable
fun PrintSettingsCard(
    settings: PrintSettings,
    onSettingsChange: (PrintSettings) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Copies
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Copies:", modifier = Modifier.weight(1f))
                IconButton(onClick = { if (settings.copies > 1) onSettingsChange(settings.copy(copies = settings.copies - 1)) }) {
                    Icon(Icons.Rounded.Remove, contentDescription = null)
                }
                Text("${settings.copies}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                IconButton(onClick = { if (settings.copies < 99) onSettingsChange(settings.copy(copies = settings.copies + 1)) }) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                }
            }

            // Orientation
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Orientation:", modifier = Modifier.weight(1f))
                SegmentedButton(
                    options = listOf("Portrait", "Landscape"),
                    selectedOption = settings.orientation,
                    onOptionSelected = { onSettingsChange(settings.copy(orientation = it)) }
                )
            }

            // Color Mode
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Color:", modifier = Modifier.weight(1f))
                SegmentedButton(
                    options = listOf("Color", "Monochrome"),
                    selectedOption = settings.colorMode,
                    onOptionSelected = { onSettingsChange(settings.copy(colorMode = it)) }
                )
            }

            // Print quality
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Qualité :", modifier = Modifier.weight(1f))
                SegmentedButton(
                    options = listOf("Brouillon", "Normal", "Optimal"),
                    selectedOption = settings.quality,
                    onOptionSelected = { onSettingsChange(settings.copy(quality = it)) }
                )
            }
        }
    }
}

@Composable
fun SegmentedButton(
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small).padding(2.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selectedOption
            Surface(
                onClick = { onOptionSelected(option) },
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.height(32.dp).padding(horizontal = 4.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(option, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun PrintJobItem(job: PrintJob) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (job.status) {
                        PrintJobStatus.Pending -> Icons.Rounded.HourglassEmpty
                        PrintJobStatus.Printing -> Icons.Rounded.Print
                        PrintJobStatus.Completed -> Icons.Rounded.CheckCircle
                        PrintJobStatus.Error -> Icons.Rounded.Error
                    },
                    contentDescription = null,
                    tint = if (job.status == PrintJobStatus.Completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(job.fileName, fontWeight = FontWeight.Bold)
                    Text(job.status.name, style = MaterialTheme.typography.bodySmall)
                }
                if (job.status == PrintJobStatus.Printing) {
                    Text("${(job.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (job.status == PrintJobStatus.Printing) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun PreviewPane(uri: Uri?, settings: PrintSettings, modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val isPdf = remember(uri) { uri != null && PrintDocument.isPdf(context, uri) }
    val colorFilter = if (settings.colorMode == "Monochrome") {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    } else {
        null
    }

    Box(
        modifier = modifier.padding(16.dp).background(Color.Gray.copy(alpha = 0.1f), MaterialTheme.shapes.large),
        contentAlignment = Alignment.Center
    ) {
        if (uri != null) {
            // The sheet as seen when holding it in the chosen orientation.
            Card(
                modifier = Modifier
                    .fillMaxWidth(if (settings.orientation == "Portrait") 0.8f else 0.95f)
                    .aspectRatio(if (settings.orientation == "Portrait") 0.7f else 1.4f),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                val pageModifier = Modifier.fillMaxSize().background(Color.White)
                if (isPdf) {
                    PdfPreview(uri, pageModifier, colorFilter)
                } else {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Preview",
                        modifier = pageModifier,
                        contentScale = ContentScale.Fit,
                        colorFilter = colorFilter
                    )
                }
            }
        } else {
            Text("No file selected for preview")
        }
    }
}

/** Renders the pages of a PDF in the background, with page navigation for multi-page documents. */
@Composable
fun PdfPreview(uri: Uri, modifier: Modifier = Modifier, colorFilter: ColorFilter? = null) {
    val context = LocalContext.current
    var pageIndex by remember(uri) { mutableIntStateOf(0) }
    val document by produceState<Result<PrintDocument>?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { PrintDocument.open(context, uri) } }
        awaitDispose { value?.getOrNull()?.let { synchronized(it) { it.close() } } }
    }
    val page by produceState<ImageBitmap?>(null, document, pageIndex) {
        val doc = document?.getOrNull() ?: return@produceState
        value = withContext(Dispatchers.IO) {
            // Copy: the document recycles its bitmap on the next render or on close.
            // Fails harmlessly if the screen was left and the document closed meanwhile.
            runCatching {
                synchronized(doc) { doc.renderPage(pageIndex, PREVIEW_DPI).copy(Bitmap.Config.ARGB_8888, false) }.asImageBitmap()
            }.getOrNull()
        }
    }
    val pageCount = document?.getOrNull()?.pageCount ?: 1

    Box(modifier, contentAlignment = Alignment.Center) {
        val current = page
        when {
            document?.isFailure == true -> Text("Aperçu indisponible")
            current != null -> Image(current, contentDescription = "Preview", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = colorFilter)
            else -> CircularProgressIndicator()
        }
        if (pageCount > 1) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { pageIndex-- }, enabled = pageIndex > 0) {
                        Icon(Icons.Rounded.ChevronLeft, contentDescription = "Page précédente")
                    }
                    Text("${pageIndex + 1} / $pageCount", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { pageIndex++ }, enabled = pageIndex < pageCount - 1) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = "Page suivante")
                    }
                }
            }
        }
    }
}

private const val PREVIEW_DPI = 100
