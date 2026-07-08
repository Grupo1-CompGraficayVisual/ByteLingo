package com.example.testingliveocr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.testingliveocr.databinding.ActivityMainBinding
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

/* Definimos una clase sencilla para guardar el código y el nombre de cada idioma */
data class Language(val code: String, val name: String)

/* Esta es la lista de idiomas que aguanta la app con ML Kit */
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

class MainActivity : AppCompatActivity() {

    /* El binding nos sirve para no andar usando findViewById en todos lados */
    private lateinit var binding: ActivityMainBinding
    
    /* Variables para guardar el texto que detectamos y el que ya está traducido */
    private var detectedText = ""
    private var translatedText = ""
    
    /* Por defecto empezamos traduciendo de Inglés a Español */
    private var sourceLanguage = supportedLanguages[0]
    private var targetLanguage = supportedLanguages[1]
    
    /* Banderas para saber en qué estado está la app */
    private var isTranslating = false
    private var isPaused = false
    private var isCropping = false
    private var capturedBitmap: Bitmap? = null
    
    /* Los motores de Google para leer texto y para traducir */
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var translator: Translator? = null

    /* Manejador para pedir el permiso de la cámara al usuario */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Permiso de cámara requerido para el OCR", Toast.LENGTH_LONG).show()
        }
    }

    /* Manejador para cuando el usuario escoge una foto de su galería */
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bitmap = android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(contentResolver, it)
                ) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
                showCapturedImage(bitmap)
            } catch (e: Exception) {
                Log.e("ByteLingo", "Error al cargar imagen de galería", e)
            }
        }
    }

    /* Aquí arranca todo cuando se abre la app */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupClickListeners()
        updateLanguageButtons()
        
        /* Checamos si ya tenemos permiso de cámara o si hay que pedirlo */
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        
        setupTranslator()
    }

    /* Configuramos la barrita de arriba y el botón de "Acerca de" */
    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_about) {
                showAboutDialog()
                true
            } else false
        }
    }

    /* Ponemos a funcionar todos los botones de la pantalla */
    private fun setupClickListeners() {
        /* El botón principal para tomar la foto o resetear la cámara */
        binding.fabCapture.setOnClickListener {
            if (!isPaused) {
                val bitmap = binding.previewView.bitmap
                if (bitmap != null) {
                    showCapturedImage(bitmap)
                }
            } else {
                resumeCamera()
            }
        }

        /* El botón para abrir la galería del cel */
        binding.fabGallery.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        /* El botón para guardar el escaneo como un archivo PDF */
        binding.fabPdf.setOnClickListener {
            val bitmapToSave = capturedBitmap ?: binding.previewView.bitmap
            if (bitmapToSave != null) {
                generateAndOpenPDF(this, bitmapToSave, translatedText)
            }
        }

        /* Botón para entrar al modo de recorte o confirmar el recorte actual */
        binding.fabCrop.setOnClickListener {
            if (!isCropping) {
                startCropping()
            } else {
                applyCrop()
            }
        }

        /* Botón por si el usuario se arrepiente de recortar */
        binding.fabCancelCrop.setOnClickListener {
            cancelCropping()
        }

        /* Los botones para elegir los idiomas */
        binding.btnSourceLang.setOnClickListener { showLanguageMenu(it, true) }
        binding.btnTargetLang.setOnClickListener { showLanguageMenu(it, false) }
    }

    /* Muestra un menú sencillito para elegir el idioma de la lista */
    private fun showLanguageMenu(view: View, isSource: Boolean) {
        val popup = PopupMenu(this, view)
        supportedLanguages.forEachIndexed { index, lang ->
            popup.menu.add(0, index, index, lang.name)
        }
        popup.setOnMenuItemClickListener { item ->
            val selected = supportedLanguages[item.itemId]
            if (isSource) sourceLanguage = selected else targetLanguage = selected
            updateLanguageButtons()
            setupTranslator()
            true
        }
        popup.show()
    }

    /* Actualiza el texto de los botones de idioma para que se vea cuál elegiste */
    private fun updateLanguageButtons() {
        binding.btnSourceLang.text = "De: ${sourceLanguage.name}"
        binding.btnTargetLang.text = "A: ${targetLanguage.name}"
    }

    /* Cuando tomamos una foto o elegimos una, la ponemos en pantalla y la procesamos */
    private fun showCapturedImage(bitmap: Bitmap) {
        capturedBitmap = bitmap
        isPaused = true
        binding.capturedImageView.setImageBitmap(bitmap)
        binding.capturedImageView.visibility = View.VISIBLE
        binding.previewView.visibility = View.GONE
        binding.fabCapture.setImageResource(R.drawable.ic_refresh)
        binding.fabCrop.visibility = View.VISIBLE
        processImage(bitmap)
    }

    /* Quita la foto capturada y vuelve a poner la cámara en vivo */
    private fun resumeCamera() {
        capturedBitmap = null
        isPaused = false
        isCropping = false
        binding.capturedImageView.visibility = View.GONE
        binding.cropOverlayContainer.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
        binding.fabCapture.setImageResource(R.drawable.ic_camera)
        binding.fabCapture.visibility = View.VISIBLE
        binding.fabCrop.visibility = View.GONE
        binding.fabCrop.setImageResource(R.drawable.ic_crop)
        binding.fabCancelCrop.visibility = View.GONE
        binding.fabGallery.visibility = View.VISIBLE
        binding.fabPdf.visibility = View.VISIBLE
        binding.controlsContainer.visibility = View.VISIBLE
        binding.tvDetectedText.text = ""
        binding.tvTranslatedText.text = ""
        detectedText = ""
        translatedText = ""
    }

    /* Activa la vista para que el usuario pueda mover el cuadrito de recorte */
    private fun startCropping() {
        isCropping = true
        binding.cropOverlayContainer.visibility = View.VISIBLE
        binding.fabCrop.setImageResource(R.drawable.ic_check)
        binding.fabCancelCrop.visibility = View.VISIBLE
        binding.fabCapture.visibility = View.GONE
        binding.fabGallery.visibility = View.GONE
        binding.fabPdf.visibility = View.GONE
        binding.controlsContainer.visibility = View.GONE
    }

    /* Hace el corte de la imagen de verdad usando lo que el usuario marcó en pantalla */
    private fun applyCrop() {
        val bitmap = capturedBitmap ?: return
        val cropRect = binding.cropOverlayView.getCropRect()
        val croppedBitmap = cropBitmap(
            bitmap,
            cropRect,
            binding.capturedImageView.width,
            binding.capturedImageView.height
        )
        showCapturedImage(croppedBitmap)
        cancelCropping()
    }

    /* Esconde el overlay de recorte y regresa los botones normales */
    private fun cancelCropping() {
        isCropping = false
        binding.cropOverlayContainer.visibility = View.GONE
        binding.fabCrop.setImageResource(R.drawable.ic_crop)
        binding.fabCancelCrop.visibility = View.GONE
        binding.fabCapture.visibility = View.VISIBLE
        binding.fabGallery.visibility = View.VISIBLE
        binding.fabPdf.visibility = View.VISIBLE
        binding.controlsContainer.visibility = View.VISIBLE
    }

    /* Función rápida para ver si tenemos permiso de usar la cámara */
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /* Aquí configuramos CameraX para que la cámara se vea en el PreviewView */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (exc: Exception) {
                Log.e("ByteLingo", "Error al iniciar cámara", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /* Configura el traductor de ML Kit y baja los modelos de idiomas si hace falta */
    private fun setupTranslator() {
        translator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage.code)
            .setTargetLanguage(targetLanguage.code)
            .build()
        translator = Translation.getClient(options)
        
        isTranslating = false
        binding.tvTranslatedText.text = "Bajando modelos de traducción..."
        
        val conditions = DownloadConditions.Builder().build()
        translator?.downloadModelIfNeeded(conditions)
            ?.addOnSuccessListener { 
                isTranslating = true 
                if (detectedText.isNotBlank()) translateText(detectedText)
                else binding.tvTranslatedText.text = ""
            }
            ?.addOnFailureListener { 
                Log.e("ByteLingo", "Error al bajar modelos", it)
                binding.tvTranslatedText.text = "Error al bajar modelos"
            }
    }

    /* Le pasa la imagen a ML Kit para que "lea" el texto que tiene */
    private fun processImage(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                detectedText = visionText.text
                binding.tvDetectedText.text = detectedText
                translateText(detectedText)
            }
            .addOnFailureListener { e ->
                Log.e("ByteLingo", "Error en OCR", e)
            }
    }

    /* Traduce el texto que ya leímos usando el traductor que configuramos antes */
    private fun translateText(text: String) {
        if (isTranslating && text.isNotBlank()) {
            translator?.translate(text)
                ?.addOnSuccessListener { 
                    translatedText = it
                    binding.tvTranslatedText.text = it
                }
                ?.addOnFailureListener { Log.e("ByteLingo", "Falló la traducción", it) }
        } else if (text.isBlank()) {
            binding.tvTranslatedText.text = ""
        }
    }

    /* Arma un documento PDF con la foto y la traducción para que lo puedas guardar */
    private fun generateAndOpenPDF(context: Context, bitmap: Bitmap, text: String) {
        val pdfDocument = PdfDocument()
        val margin = 50f
        
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 20f
            isAntiAlias = true
        }
        
        val maxWidth = bitmap.width.toFloat() - (2 * margin)
        val lines = wrapText(text, paint, maxWidth)
        val lineHeight = paint.descent() - paint.ascent() + 10f
        
        val pageWidth = bitmap.width
        val pageHeight = bitmap.height + 400
        
        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var currentPage = pdfDocument.startPage(pageInfo)
        var canvas = currentPage.canvas

        canvas.drawBitmap(bitmap, 0f, 0f, null)

        var currentY = bitmap.height.toFloat() + 80f

        for (line in lines) {
            if (currentY + lineHeight > pageHeight - margin) {
                pdfDocument.finishPage(currentPage)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                currentPage = pdfDocument.startPage(pageInfo)
                canvas = currentPage.canvas
                currentY = margin + 20f
            }
            
            canvas.drawText(line, margin, currentY, paint)
            currentY += lineHeight
        }

        pdfDocument.finishPage(currentPage)

        val file = File(context.cacheDir, "ByteLingo_Scan_${System.currentTimeMillis()}.pdf")
        try {
            pdfDocument.writeTo(FileOutputStream(file))
            openPDF(context, file)
        } catch (e: Exception) {
            Log.e("ByteLingo", "Error al escribir el PDF", e)
        } finally {
            pdfDocument.close()
        }
    }

    /* Separa el texto en varias líneas para que no se salga del ancho de la página del PDF */
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

    /* Abre un selector para que el usuario elija con qué app quiere ver su nuevo PDF */
    private fun openPDF(context: Context, file: File) {
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

    /* Muestra el cuadrito de información sobre quién hizo la app */
    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Acerca de ByteLingo")
            .setMessage("ByteLingo es una app para traducir texto en vivo usando OCR.\n\nDesarrolladores:\n• Jose Meraz\n• Carlos Lopez\n• Gilberto Valladares\n• Joseph Rodriguez\n• Santiago Chavarria\n• Eduardo Contreras\n• Luis Ayestas")
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /* Esta es la lógica pesada para recortar el bitmap real usando las coordenadas de la pantalla */
    private fun cropBitmap(bitmap: Bitmap, rect: android.graphics.Rect, containerWidth: Int, containerHeight: Int): Bitmap {
        val bitmapWidth = bitmap.width.toFloat()
        val bitmapHeight = bitmap.height.toFloat()

        /* Calculamos cómo se ajustó la imagen al tamaño de la pantalla */
        val scale = minOf(containerWidth.toFloat() / bitmapWidth, containerHeight.toFloat() / bitmapHeight)
        val scaledWidth = bitmapWidth * scale
        val scaledHeight = bitmapHeight * scale

        /* Quitamos los espacios negros laterales o verticales si los hay */
        val offsetX = (containerWidth - scaledWidth) / 2
        val offsetY = (containerHeight - scaledHeight) / 2

        /* Mapeamos el cuadrito del usuario a los píxeles reales de la foto original */
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

        val safeLeft = actualLeft.toInt().coerceIn(0, (bitmapWidth - cropWidth).toInt())
        val safeTop = actualTop.toInt().coerceIn(0, (bitmapHeight - cropHeight).toInt())

        /* Devolvemos el pedacito de foto recortado */
        return Bitmap.createBitmap(bitmap, safeLeft, safeTop, cropWidth, cropHeight)
    }

    /* Cerramos el traductor cuando cerramos la app para no gastar memoria a lo loco */
    override fun onDestroy() {
        super.onDestroy()
        translator?.close()
    }
}
