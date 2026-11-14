package com.jorge.koreandiary.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.toArgb
import com.jorge.koreandiary.scene.art.CustomDrawing
import com.jorge.koreandiary.ui.ScenePalette
import java.io.ByteArrayOutputStream

/**
 * La imagen que Claude mira para revisar su dibujo: la caja de 100 × 100 a 512 px sobre papel crema,
 * con una línea fina en y = 100 (el suelo), rellenos planos y sin el filtro de acuarela. No es una
 * pantalla: los colores fijos son el papel claro de la app, para que Claude vea lo mismo que tú.
 */
object DrawingSnapshot {
    const val SIZE = 512

    private const val PAPER = 0xFFFAF7EE.toInt()
    private const val GROUND_LINE = 0xFFD6CFBF.toInt()

    fun png(drawing: CustomDrawing): ByteArray {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER)
        // Un margen alrededor de la caja: las formas pueden salirse un poco (hasta -10 y 110).
        val side = SIZE * 0.84f
        val left = (SIZE - side) / 2
        val top = SIZE * 0.06f
        val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GROUND_LINE; strokeWidth = 2f }
        canvas.drawLine(0f, top + side, SIZE.toFloat(), top + side, ground)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val path = Path()
        for (m in drawing.boxMarks(side, left, top)) {
            val o = m.outline
            if (o.size < 6) continue
            path.reset()
            path.moveTo(o[0], o[1])
            var k = 2
            while (k + 1 < o.size) {
                path.lineTo(o[k], o[k + 1])
                k += 2
            }
            path.close()
            paint.color = ScenePalette.watercolor(m.color, dark = false).toArgb()
            // Como en la escena: el pigmento no cubre del todo.
            paint.alpha = (m.alpha * 0.85f * 255).toInt().coerceIn(0, 255)
            canvas.drawPath(path, paint)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
