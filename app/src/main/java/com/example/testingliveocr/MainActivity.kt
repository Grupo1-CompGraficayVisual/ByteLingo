package com.example.testingliveocr

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.testingliveocr.ui.theme.TestingLiveOCRTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TestingLiveOCRTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ByteLingoApp() /* Lanzar la aplicacion directamente */
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ByteLingoApp() { /* Revisar si hay permiso de camara, y si no, pedirlos */
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    if (cameraPermissionState.status.isGranted) {
        MainScreen()
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Camera permission is required for OCR")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                Text("Request Permission")
            }
        }
    }
}

data class Language(val code: String, val name: String)

val supportedLanguages = listOf(
    Language(TranslateLanguage.ENGLISH, "English"),
    Language(TranslateLanguage.SPANISH, "Spanish"),
    Language(TranslateLanguage.FRENCH, "French"),
    Language(TranslateLanguage.GERMAN, "German"),
    Language(TranslateLanguage.ITALIAN, "Italian"),
    Language(TranslateLanguage.PORTUGUESE, "Portuguese"),
    Language(TranslateLanguage.RUSSIAN, "Russian"),
    Language(TranslateLanguage.JAPANESE, "Japanese"),
    Language(TranslateLanguage.CHINESE, "Chinese"),
    Language(TranslateLanguage.ARABIC, "Arabic"),
    Language(TranslateLanguage.HINDI, "Hindi")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var detectedText by remember { mutableStateOf("") }
    var translatedText by remember { mutableStateOf("") }
    var sourceLanguage by remember { mutableStateOf(supportedLanguages[0]) } // English
    var targetLanguage by remember { mutableStateOf(supportedLanguages[1]) } // Spanish
    var isTranslating by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastPreviewView by remember { mutableStateOf<PreviewView?>(null) }
    val context = LocalContext.current

    val translator = remember(sourceLanguage, targetLanguage) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage.code)
            .setTargetLanguage(targetLanguage.code)
            .build()
        Translation.getClient(options)
    }

    // Handle translator lifecycle
    DisposableEffect(translator) {
        onDispose {
            translator.close()
        }
    }

    // Download models if needed
    LaunchedEffect(translator) {
        isTranslating = false
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { isTranslating = true }
            .addOnFailureListener { isTranslating = false }
    }

    // Translate detected text when it changes
    LaunchedEffect(detectedText, isTranslating) {
        if (isTranslating && detectedText.isNotBlank()) {
            translator.translate(detectedText)
                .addOnSuccessListener { translatedText = it }
                .addOnFailureListener { Log.e("ByteLingo", "Translation failed", it) }
        } else if (detectedText.isBlank()) {
            translatedText = ""
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ByteLingo", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "About")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(
                    onClick = {
                        val bitmapToSave = capturedBitmap ?: lastPreviewView?.bitmap
                        if (bitmapToSave != null) {
                            generateAndOpenPDF(context, bitmapToSave, translatedText)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = "Save as PDF")
                }
                
                FloatingActionButton(
                    onClick = {
                        if (!isPaused) {
                            capturedBitmap = lastPreviewView?.bitmap
                        } else {
                            capturedBitmap = null
                        }
                        isPaused = !isPaused
                    },
                    containerColor = if (isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                ) {
                    Icon(
                        if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Resume" else "Pause"
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Camera View (Top half)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                CameraView(
                    onTextDetected = { text ->
                        if (!isPaused) {
                            detectedText = text
                        }
                    },
                    onPreviewViewReady = { lastPreviewView = it }
                )

                if (isPaused && capturedBitmap != null) {
                    Image(
                        bitmap = capturedBitmap!!.asImageBitmap(),
                        contentDescription = "Frozen frame",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("PAUSED", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    }
                }
            }

            // Language Selection and Results (Bottom half)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LanguageSelector(
                        label = "From",
                        selectedLanguage = sourceLanguage,
                        onLanguageSelected = { sourceLanguage = it }
                    )
                    Text("→", fontSize = 24.sp)
                    LanguageSelector(
                        label = "To",
                        selectedLanguage = targetLanguage,
                        onLanguageSelected = { targetLanguage = it }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Detected Text:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(text = detectedText)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Translated Text:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (!isTranslating && detectedText.isNotBlank()) {
                        Text("Downloading translation models...", color = Color.Gray)
                    } else {
                        Text(text = translatedText)
                    }
                }
            }
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}

@Composable
fun LanguageSelector(
    label: String,
    selectedLanguage: Language,
    onLanguageSelected: (Language) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label: ${selectedLanguage.name}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            supportedLanguages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.name) },
                    onClick = {
                        onLanguageSelected(language)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun CameraView(
    onTextDetected: (String) -> Unit,
    onPreviewViewReady: (PreviewView) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).also {
                onPreviewViewReady(it)
            }
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor) { imageProxy ->
                            processImageProxy(recognizer, imageProxy, onTextDetected)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (exc: Exception) {
                    Log.e("ByteLingo", "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImageProxy(
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
    imageProxy: ImageProxy,
    onTextDetected: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // Filter out small or empty text to reduce flickering
                val resultText = visionText.text
                if (resultText.isNotBlank()) {
                    onTextDetected(resultText)
                }
            }
            .addOnFailureListener { e ->
                Log.e("ByteLingo", "Text recognition failed", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}

private fun generateAndOpenPDF(context: android.content.Context, bitmap: Bitmap, text: String) {
    val pdfDocument = PdfDocument()
    // Create page with some extra space at bottom for text
    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height + 400, 1).create()
    val page = pdfDocument.startPage(pageInfo)
    val canvas = page.canvas

    // Draw the image
    canvas.drawBitmap(bitmap, 0f, 0f, null)

    // Draw the translated text
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        textSize = 40f
        isAntiAlias = true
    }
    
    val textY = bitmap.height.toFloat() + 80f
    val margin = 50f
    val maxWidth = bitmap.width.toFloat() - (2 * margin)
    
    val lines = wrapText(text, paint, maxWidth)
    var currentY = textY
    for (line in lines) {
        if (currentY + 50f > pageInfo.pageHeight) break // Basic overflow protection
        canvas.drawText(line, margin, currentY, paint)
        currentY += paint.descent() - paint.ascent() + 10f
    }

    pdfDocument.finishPage(page)

    val file = File(context.cacheDir, "ByteLingo_Scan_${System.currentTimeMillis()}.pdf")
    try {
        pdfDocument.writeTo(FileOutputStream(file))
        openPDF(context, file)
    } catch (e: Exception) {
        Log.e("ByteLingo", "Error writing PDF", e)
    } finally {
        pdfDocument.close()
    }
}

private fun wrapText(text: String, paint: android.graphics.Paint, maxWidth: Float): List<String> {
    val words = text.split(Regex("\\s+"))
    val lines = mutableListOf<String>()
    var currentLine = StringBuilder()

    for (word in words) {
        val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
        val width = paint.measureText(testLine)
        if (width <= maxWidth) {
            currentLine.append(if (currentLine.isEmpty()) word else " $word")
        } else {
            if (currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
            }
            currentLine = StringBuilder(word)
        }
    }
    if (currentLine.isNotEmpty()) {
        lines.add(currentLine.toString())
    }
    return lines
}

private fun openPDF(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Open PDF with..."))
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About ByteLingo") },
        text = {
            Column {
                Text("ByteLingo is a live OCR translator app.")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Developers:", fontWeight = FontWeight.Bold)
                Text("• Jose Meraz")
                Text("• Carlos Lopez")
                Text("• Gilberto Valladares")
                Text("• Joseph Rodriguez")
                Text("• Santiago Chavarria")
                Text("• Eduardo Contreras")
                Text("• Luis Ayestas")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}
