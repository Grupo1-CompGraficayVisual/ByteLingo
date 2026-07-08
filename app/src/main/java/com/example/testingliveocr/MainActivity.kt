package com.example.testingliveocr

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
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
                    ByteLingoApp() // Arrancamos la app directo
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ByteLingoApp() { // Aquí revisamos si tenemos permiso de usar la cámara
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    if (cameraPermissionState.status.isGranted) {
        MainScreen() // Si hay permiso, vamos a la pantalla principal
    } else {
        // Si no hay permiso, le pedimos al usuario que nos lo dé
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Ocupamos permiso de la cámara para que el OCR jale")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                Text("Dar permiso")
            }
        }
    }
}

data class Language(val code: String, val name: String)

// Esta es la lista de idiomas que aguanta la app usando ML Kit
val supportedLanguages = listOf(
    Language(TranslateLanguage.ENGLISH, "Inglés"),
    Language(TranslateLanguage.SPANISH, "Español"),
    Language(TranslateLanguage.FRENCH, "Francés"),
    Language(TranslateLanguage.GERMAN, "Alemán"),
    Language(TranslateLanguage.ITALIAN, "Italiano"),
    Language(TranslateLanguage.PORTUGUESE, "Portugués"),
    Language(TranslateLanguage.RUSSIAN, "Ruso"),
    Language(TranslateLanguage.JAPANESE, "Japonés"),
    Language(TranslateLanguage.CHINESE, "Chino"),
    Language(TranslateLanguage.ARABIC, "Árabe"),
    Language(TranslateLanguage.HINDI, "Hindi")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    // Variables para guardar el texto detectado, el traducido y los idiomas seleccionados
    var detectedText by remember { mutableStateOf("") }
    var translatedText by remember { mutableStateOf("") }
    var sourceLanguage by remember { mutableStateOf(supportedLanguages[0]) } 
    var targetLanguage by remember { mutableStateOf(supportedLanguages[1]) } 
    var isTranslating by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var isCropping by remember { mutableStateOf(false) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastPreviewView by remember { mutableStateOf<PreviewView?>(null) }
    val context = LocalContext.current

    // ML Kit Recognizer
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // Función para procesar captura de cámara o imagen recortada
    val processCapturedBitmap = { bitmap: Bitmap ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                detectedText = visionText.text
            }
            .addOnFailureListener { e ->
                Log.e("ByteLingo", "Error en OCR", e)
            }
    }

    // Launcher para elegir imagen de la galería
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bitmap = android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(context.contentResolver, it)
                ) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
                capturedBitmap = bitmap
                isPaused = true
                isCropping = false
                
                processCapturedBitmap(bitmap)
            } catch (e: Exception) {
                Log.e("ByteLingo", "Error al cargar imagen de galería", e)
            }
        }
    }

    // Configuramos el traductor dependiendo de los idiomas que elijas
    val translator = remember(sourceLanguage, targetLanguage) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage.code)
            .setTargetLanguage(targetLanguage.code)
            .build()
        Translation.getClient(options)
    }

    // Limpiamos el traductor cuando ya no lo ocupemos
    DisposableEffect(translator) {
        onDispose {
            translator.close()
        }
    }

    // Aquí bajamos los modelos de traducción si no los tienes en el cel
    LaunchedEffect(translator) {
        isTranslating = false
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { isTranslating = true }
            .addOnFailureListener { isTranslating = false }
    }

    // Cada que cambie el texto detectado, lo mandamos a traducir
    LaunchedEffect(detectedText, isTranslating) {
        if (isTranslating && detectedText.isNotBlank()) {
            translator.translate(detectedText)
                .addOnSuccessListener { translatedText = it }
                .addOnFailureListener { Log.e("ByteLingo", "Falló la traducción", it) }
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
                        Icon(Icons.Default.Info, contentDescription = "Acerca de")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                // Botón para generar el PDF
                if (!isCropping) {
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
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "Guardar como PDF")
                    }
                }
                
                // Botón para Galería
                if (!isPaused && !isCropping) {
                    FloatingActionButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        containerColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Galería")
                    }
                }

                // Botón para Recortar (Solo si hay imagen y no estamos ya recortando)
                if (isPaused && capturedBitmap != null && !isCropping) {
                    FloatingActionButton(
                        onClick = { isCropping = true },
                        containerColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Icon(Icons.Default.Crop, contentDescription = "Recortar")
                    }
                }

                // Botón para Capturar / Confirmar Recorte / Nueva Foto
                FloatingActionButton(
                    onClick = {
                        if (isCropping) {
                            // La lógica de recorte se maneja dentro del componente CropOverlay
                            // pero necesitamos una forma de disparar el "terminar" si no hay botón interno
                        } else if (!isPaused) {
                            val bitmap = lastPreviewView?.bitmap
                            if (bitmap != null) {
                                capturedBitmap = bitmap
                                isPaused = true
                                processCapturedBitmap(bitmap)
                            }
                        } else {
                            capturedBitmap = null
                            isPaused = false
                            isCropping = false
                            detectedText = ""
                            translatedText = ""
                        }
                    },
                    containerColor = if (isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                ) {
                    Icon(
                        if (isPaused) Icons.Default.Refresh else Icons.Default.CameraAlt,
                        contentDescription = if (isPaused) "Nueva Foto" else "Capturar"
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
            // Parte de arriba: Cámara, Imagen o Recortador
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (!isPaused) {
                    CameraView(
                        onPreviewViewReady = { lastPreviewView = it }
                    )
                }

                if (isPaused && capturedBitmap != null) {
                    if (isCropping) {
                        CropOverlay(
                            bitmap = capturedBitmap!!,
                            onCropDone = { cropped ->
                                capturedBitmap = cropped
                                isCropping = false
                                processCapturedBitmap(cropped)
                            },
                            onCancel = { isCropping = false }
                        )
                    } else {
                        Image(
                            bitmap = capturedBitmap!!.asImageBitmap(),
                            contentDescription = "Imagen capturada",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }

            // Parte de abajo: Selección de idiomas y resultados (Ocultar si estamos recortando para más espacio)
            if (!isCropping) {
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
                            label = "De",
                            selectedLanguage = sourceLanguage,
                            onLanguageSelected = { sourceLanguage = it }
                        )
                        Text("→", fontSize = 24.sp)
                        LanguageSelector(
                            label = "A",
                            selectedLanguage = targetLanguage,
                            onLanguageSelected = { targetLanguage = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Texto Detectado:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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

                    Text("Texto Traducido:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (!isTranslating && detectedText.isNotBlank()) {
                            Text("Bajando modelos de traducción...", color = Color.Gray)
                        } else {
                            Text(text = translatedText)
                        }
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
fun CropOverlay(
    bitmap: Bitmap,
    onCropDone: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var rect by remember { mutableStateOf(Rect(100f, 100f, 500f, 500f)) }
    
    // Puntos de control (esquinas)
    val handleSize = 40.dp
    val handleSizePx = with(LocalDensity.current) { handleSize.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { containerSize = it.size }
    ) {
        // Mostrar la imagen de fondo
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // Overlay de recorte
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Dibujar el área sombreada fuera del recorte
            // (Simplificado: solo dibujamos el rectángulo de recorte por ahora)
            drawRect(
                color = Color.White,
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = 4f)
            )
            
            // Dibujar las esquinas
            drawCircle(Color.White, radius = handleSizePx / 4, center = rect.topLeft)
            drawCircle(Color.White, radius = handleSizePx / 4, center = rect.topRight)
            drawCircle(Color.White, radius = handleSizePx / 4, center = rect.bottomLeft)
            drawCircle(Color.White, radius = handleSizePx / 4, center = rect.bottomRight)
        }

        // Detectores de gestos para las esquinas
        // Superior Izquierda
        Box(modifier = Modifier
            .offset { IntOffset(rect.left.toInt() - (handleSizePx/2).toInt(), rect.top.toInt() - (handleSizePx/2).toInt()) }
            .size(handleSize)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    rect = rect.copy(left = rect.left + dragAmount.x, top = rect.top + dragAmount.y)
                }
            }
        )
        // Superior Derecha
        Box(modifier = Modifier
            .offset { IntOffset(rect.right.toInt() - (handleSizePx/2).toInt(), rect.top.toInt() - (handleSizePx/2).toInt()) }
            .size(handleSize)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    rect = rect.copy(right = rect.right + dragAmount.x, top = rect.top + dragAmount.y)
                }
            }
        )
        // Inferior Izquierda
        Box(modifier = Modifier
            .offset { IntOffset(rect.left.toInt() - (handleSizePx/2).toInt(), rect.bottom.toInt() - (handleSizePx/2).toInt()) }
            .size(handleSize)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    rect = rect.copy(left = rect.left + dragAmount.x, bottom = rect.bottom + dragAmount.y)
                }
            }
        )
        // Inferior Derecha
        Box(modifier = Modifier
            .offset { IntOffset(rect.right.toInt() - (handleSizePx/2).toInt(), rect.bottom.toInt() - (handleSizePx/2).toInt()) }
            .size(handleSize)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    rect = rect.copy(right = rect.right + dragAmount.x, bottom = rect.bottom + dragAmount.y)
                }
            }
        )

        // Botones de acción
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                Text("Cancelar")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = {
                // Calcular el recorte real basado en el bitmap
                val cropped = cropBitmap(bitmap, rect, containerSize)
                onCropDone(cropped)
            }) {
                Text("Aplicar Recorte")
            }
        }
    }
}

private fun cropBitmap(bitmap: Bitmap, rect: Rect, containerSize: IntSize): Bitmap {
    // 1. Encontrar cómo se escaló la imagen en el contenedor (ContentScale.Fit)
    val containerWidth = containerSize.width.toFloat()
    val containerHeight = containerSize.height.toFloat()
    val bitmapWidth = bitmap.width.toFloat()
    val bitmapHeight = bitmap.height.toFloat()

    val scale = minOf(containerWidth / bitmapWidth, containerHeight / bitmapHeight)
    val scaledWidth = bitmapWidth * scale
    val scaledHeight = bitmapHeight * scale

    val offsetX = (containerWidth - scaledWidth) / 2
    val offsetY = (containerHeight - scaledHeight) / 2

    // 2. Mapear el rect de la pantalla al rect del bitmap
    val left = ((rect.left - offsetX) / scale).coerceIn(0f, bitmapWidth)
    val top = ((rect.top - offsetY) / scale).coerceIn(0f, bitmapHeight)
    val right = ((rect.right - offsetX) / scale).coerceIn(0f, bitmapWidth)
    val bottom = ((rect.bottom - offsetY) / scale).coerceIn(0f, bitmapHeight)

    val actualLeft = minOf(left, right)
    val actualTop = minOf(top, bottom)
    val actualRight = maxOf(left, right)
    val actualBottom = maxOf(top, bottom)

    val cropWidth = (actualRight - actualLeft).toInt().coerceAtLeast(1)
    val cropHeight = (actualBottom - actualTop).toInt().coerceAtLeast(1)

    // Evitar que el recorte se salga de los bordes finales por redondeo
    val safeLeft = actualLeft.toInt().coerceIn(0, (bitmapWidth - cropWidth).toInt())
    val safeTop = actualTop.toInt().coerceIn(0, (bitmapHeight - cropHeight).toInt())

    return Bitmap.createBitmap(bitmap, safeLeft, safeTop, cropWidth, cropHeight)
}

@Composable
fun LanguageSelector(
    label: String,
    selectedLanguage: Language,
    onLanguageSelected: (Language) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    // Un simple menú desplegable para elegir el idioma
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
    onPreviewViewReady: (PreviewView) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // Integramos la vista de la cámara de Android en Compose
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).also {
                onPreviewViewReady(it)
            }
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                // Configuramos la previsualización
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview
                    )
                } catch (exc: Exception) {
                    Log.e("ByteLingo", "No se pudo conectar la cámara", exc)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

// Esta función arma un PDF con la captura de pantalla y el texto traducido
private fun generateAndOpenPDF(context: android.content.Context, bitmap: Bitmap, text: String) {
    val pdfDocument = PdfDocument()
    // Le damos un poco más de espacio abajo para que quepa el texto
    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height + 400, 1).create()
    val page = pdfDocument.startPage(pageInfo)
    val canvas = page.canvas

    // Dibujamos la imagen capturada
    canvas.drawBitmap(bitmap, 0f, 0f, null)

    // Configuramos cómo se va a ver el texto en el PDF
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        textSize = 20f
        isAntiAlias = true
    }
    
    val textY = bitmap.height.toFloat() + 80f
    val margin = 50f
    val maxWidth = bitmap.width.toFloat() - (2 * margin)
    
    // Dividimos el texto en renglones para que no se salga de la hoja
    val lines = wrapText(text, paint, maxWidth)
    var currentY = textY
    for (line in lines) {
        if (currentY + 50f > pageInfo.pageHeight) break // Por si se nos acaba la hoja
        canvas.drawText(line, margin, currentY, paint)
        currentY += paint.descent() - paint.ascent() + 10f
    }

    pdfDocument.finishPage(page)

    // Guardamos el archivo en la cache temporal
    val file = File(context.cacheDir, "ByteLingo_Scan_${System.currentTimeMillis()}.pdf")
    try {
        pdfDocument.writeTo(FileOutputStream(file))
        openPDF(context, file) // Intentamos abrirlo de una vez
    } catch (e: Exception) {
        Log.e("ByteLingo", "Error al escribir el PDF", e)
    } finally {
        pdfDocument.close()
    }
}

// Ayuda a separar el texto en varias líneas si está muy largo
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

// Lanza un intent para que el usuario elija con qué app abrir su PDF
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
    context.startActivity(Intent.createChooser(intent, "Abrir PDF con..."))
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Acerca de ByteLingo") },
        text = {
            Column {
                Text("ByteLingo es una app para traducir texto en vivo usando OCR.")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Desarrolladores:", fontWeight = FontWeight.Bold)
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
                Text("Cerrar")
            }
        }
    )
}
