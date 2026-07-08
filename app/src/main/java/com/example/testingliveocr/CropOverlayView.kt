package com.example.testingliveocr

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/* Esta clase es la que dibuja el cuadrito blanco para recortar la foto encima de la imagen */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /* El rectángulo inicial donde va a empezar el recorte */
    private val rect = RectF(100f, 100f, 500f, 500f)
    
    /* El pincel para pintar los circulitos de las esquinas */
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    
    /* El pincel para la línea del borde del recorte */
    private val linePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    
    /* El pincel para ponerle esa sombrita gris a lo que queda fuera del recorte */
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#88000000")
    }

    /* Qué tan grandes son los circulitos de las esquinas */
    private val handleRadius = 30f
    
    /* Variable para saber qué esquina estamos jalando (0: Arriba-Izquierda, 1: Arriba-Derecha, 2: Abajo-Izquierda, 3: Abajo-Derecha) */
    private var activeHandle = -1 

    /* Aquí es donde dibujamos todo el relajo en la pantalla */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        /* Pintamos el fondo oscuro en las 4 áreas alrededor del cuadrito de recorte */
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, backgroundPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, backgroundPaint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, backgroundPaint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), backgroundPaint)

        /* Dibujamos el borde del cuadrito */
        canvas.drawRect(rect, linePaint)

        /* Dibujamos los 4 circulitos en las esquinas para que el usuario sepa de dónde agarrar */
        canvas.drawCircle(rect.left, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.left, rect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.bottom, handleRadius, handlePaint)
    }

    /* Esta función detecta cuando el usuario pone el dedo y mueve el recorte */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            /* Cuando recién toca la pantalla */
            MotionEvent.ACTION_DOWN -> {
                activeHandle = getActiveHandle(x, y)
                return activeHandle != -1
            }
            /* Cuando arrastra el dedo */
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle != -1) {
                    updateRect(x, y)
                    invalidate() /* Le decimos que se vuelva a dibujar porque ya cambió de lugar */
                }
            }
            /* Cuando suelta el dedo */
            MotionEvent.ACTION_UP -> {
                activeHandle = -1
            }
        }
        return true
    }

    /* Revisa si el dedo está cerca de alguna de las 4 esquinas */
    private fun getActiveHandle(x: Float, y: Float): Int {
        if (isNear(x, y, rect.left, rect.top)) return 0
        if (isNear(x, y, rect.right, rect.top)) return 1
        if (isNear(x, y, rect.left, rect.bottom)) return 2
        if (isNear(x, y, rect.right, rect.bottom)) return 3
        return -1
    }

    /* Una simple cuenta matemática para ver la distancia entre el toque y la esquina */
    private fun isNear(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val dist = Math.sqrt(Math.pow((x1 - x2).toDouble(), 2.0) + Math.pow((y1 - y2).toDouble(), 2.0))
        return dist < handleRadius * 2
    }

    /* Actualiza las coordenadas del rectángulo según qué esquina se esté moviendo */
    private fun updateRect(x: Float, y: Float) {
        val minSize = 100f /* El tamaño mínimo para que el cuadro no desaparezca */
        when (activeHandle) {
            0 -> {
                rect.left = x.coerceIn(0f, rect.right - minSize)
                rect.top = y.coerceIn(0f, rect.bottom - minSize)
            }
            1 -> {
                rect.right = x.coerceIn(rect.left + minSize, width.toFloat())
                rect.top = y.coerceIn(0f, rect.bottom - minSize)
            }
            2 -> {
                rect.left = x.coerceIn(0f, rect.right - minSize)
                rect.bottom = y.coerceIn(rect.top + minSize, height.toFloat())
            }
            3 -> {
                rect.right = x.coerceIn(rect.left + minSize, width.toFloat())
                rect.bottom = y.coerceIn(rect.top + minSize, height.toFloat())
            }
        }
    }

    /* Nos devuelve las coordenadas finales del recorte para poder cortar la imagen de verdad */
    fun getCropRect(): Rect {
        return Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
    }
}
