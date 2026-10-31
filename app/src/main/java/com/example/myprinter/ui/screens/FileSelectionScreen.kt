package com.example.myprinter.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.myprinter.R
import com.example.myprinter.ui.viewmodel.PrintJobViewModel

import androidx.compose.ui.tooling.preview.Preview
import com.example.myprinter.ui.theme.MyPrinterTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSelectionScreen(
    viewModel: PrintJobViewModel,
    onNavigateToPrinter: () -> Unit,
    onNavigateToPrintPreview: (Uri) -> Unit
) {
    val selectedUri by viewModel.selectedUri.collectAsState()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        viewModel.selectFile(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (selectedUri == null) {
                Icon(
                    imageVector = Icons.Rounded.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(100.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.file_none),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.file_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(32.dp))
                ElevatedButton(
                    onClick = {
                        launcher.launch(arrayOf("image/*", "application/pdf"))
                    },
                    modifier = Modifier.height(56.dp).padding(horizontal = 32.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.file_choose), fontSize = 16.sp)
                }
            } else {
                PreviewSection(uri = selectedUri!!)
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = onNavigateToPrinter,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(Icons.Rounded.Usb, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.file_printer))
                    }
                    ElevatedButton(
                        onClick = { selectedUri?.let { onNavigateToPrintPreview(it) } },
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.elevatedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Rounded.Print, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.file_print))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = { viewModel.selectFile(null) }) {
                    Text(stringResource(R.string.file_change))
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun FileSelectionScreenPreview() {
    MyPrinterTheme {
        FileSelectionScreen(
            viewModel = PrintJobViewModel(),
            onNavigateToPrinter = {},
            onNavigateToPrintPreview = {}
        )
    }
}

@Composable
fun PreviewSection(uri: Uri) {
    val context = LocalContext.current
    val mimeType = context.contentResolver.getType(uri)
    val isImage = mimeType?.startsWith("image/") == true

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (isImage) {
                AsyncImage(
                    model = uri,
                    contentDescription = stringResource(R.string.file_selected_image),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else if (mimeType == "application/pdf") {
                PdfPreview(uri, Modifier.fillMaxSize())
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.file_unsupported),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
