package com.example.myprinter.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myprinter.R
import com.example.myprinter.ui.viewmodel.ScanFormat
import com.example.myprinter.ui.viewmodel.ScanViewModel
import com.example.myprinter.usb.PrinterConnectionState
import com.example.myprinter.usb.ScanColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    onBack: () -> Unit,
    onNavigateToPrinter: () -> Unit,
    onPrint: (Uri) -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val pages by viewModel.pages.collectAsState()
    val options by viewModel.options.collectAsState()
    val format by viewModel.format.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val canScan = (connectionState as? PrinterConnectionState.Connected)?.canScan == true

    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { viewModel.savePdf(it) }
    }
    // JPEG: one file per page, so the user picks a folder.
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { viewModel.saveJpegs(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!canScan) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                if (connectionState is PrinterConnectionState.Connected) R.string.scan_unsupported
                                else R.string.scan_not_connected
                            )
                        )
                        OutlinedButton(onClick = onNavigateToPrinter, modifier = Modifier.align(Alignment.End)) {
                            Icon(Icons.Rounded.Usb, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.file_printer))
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.setting_color))
                        SegmentedButton(
                            options = listOf(
                                ScanColor.Color to stringResource(R.string.color_color),
                                ScanColor.Gray to stringResource(R.string.scan_gray)
                            ),
                            selectedOption = options.color,
                            onOptionSelected = { viewModel.updateOptions(options.copy(color = it)) }
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.scan_resolution))
                        SegmentedButton(
                            options = listOf(150, 300, 600).map { it to stringResource(R.string.scan_dpi, it) },
                            selectedOption = options.dpi,
                            onOptionSelected = { viewModel.updateOptions(options.copy(dpi = it)) }
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.scan_format))
                        SegmentedButton(
                            options = listOf(
                                ScanFormat.Pdf to stringResource(R.string.scan_format_pdf),
                                ScanFormat.Jpeg to stringResource(R.string.scan_format_jpeg)
                            ),
                            selectedOption = format,
                            onOptionSelected = { viewModel.updateFormat(it) }
                        )
                    }
                }
            }

            ElevatedButton(
                onClick = { viewModel.scanPage() },
                enabled = canScan && !scanning,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.scan_in_progress), fontSize = 18.sp)
                } else {
                    Icon(Icons.Rounded.DocumentScanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(if (pages.isEmpty()) R.string.scan_page else R.string.scan_add_page),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (pages.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.scan_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    pluralStringResource(R.plurals.scan_pages, pages.size, pages.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                LazyRow(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(pages, key = { _, page -> page.file.name }) { index, page ->
                        Card(modifier = Modifier.fillMaxHeight().aspectRatio(0.707f)) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                AsyncImage(
                                    model = page.file,
                                    contentDescription = stringResource(R.string.scan_page_number, index + 1),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                                FilledTonalIconButton(
                                    onClick = { viewModel.removePage(page) },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                ) {
                                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.scan_delete_page))
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            when (format) {
                                ScanFormat.Pdf -> saveLauncher.launch("${viewModel.defaultName()}.pdf")
                                ScanFormat.Jpeg -> folderLauncher.launch(null)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.scan_save), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val send = when (format) {
                                    ScanFormat.Pdf -> Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        putExtra(Intent.EXTRA_STREAM, viewModel.pdfUri())
                                    }
                                    ScanFormat.Jpeg -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                        type = "image/jpeg"
                                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(viewModel.jpegUris()))
                                    }
                                }
                                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(send, null))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.scan_share), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { scope.launch { onPrint(viewModel.pdfUri()) } },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Print, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.file_print), maxLines = 1)
                    }
                }
            }
        }
    }
}
