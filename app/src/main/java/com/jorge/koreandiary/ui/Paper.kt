package com.jorge.koreandiary.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.jorge.koreandiary.settings.DarkPaper
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

enum class PaperTexture { NONE, CALLIGRAPHY }

/** Colores del lienzo. Van aparte del tema porque el papel tiene su propia identidad. */
@Immutable
data class PaperStyle(
    val background: Color,
    val line: Color,
    val ink: Color,
    /** Sombreado de la zona ya revisada. */
    val reviewedTint: Color,
    val texture: PaperTexture = PaperTexture.NONE,
)

val LightPaper = PaperStyle(
    background = Color(0xFFFFFDF7),
    line = Color(0xFFC9D2DF),
    ink = Color(0xFF1A1A2E),
    reviewedTint = Color(0x10000000),
)

/** Papel negro de caligrafía: negro cálido, fibras sutiles, líneas ocres y tinta marfil. */
val CalligraphyPaper = PaperStyle(
    background = Color(0xFF15120E),
    line = Color(0x806B5536),
    ink = Color(0xFFEFE7D6),
    reviewedTint = Color(0x12EFE7D6),
    texture = PaperTexture.CALLIGRAPHY,
)

/** Negro puro para AMOLED: los píxeles negros quedan apagados. */
val PureBlackPaper = PaperStyle(
    background = Color(0xFF000000),
    line = Color(0xFF262626),
    ink = Color(0xFFFFFFFF),
    reviewedTint = Color(0x14FFFFFF),
)

fun paperFor(dark: Boolean, darkPaper: String): PaperStyle = when {
    !dark -> LightPaper
    darkPaper == DarkPaper.PURE_BLACK -> PureBlackPaper
    else -> CalligraphyPaper
}

val LocalPaperStyle = staticCompositionLocalOf { LightPaper }

/** Fondo del papel. La textura se genera una vez por tamaño y se reutiliza en cada trazo. */
fun Modifier.paperBackground(style: PaperStyle): Modifier =
    if (style.texture == PaperTexture.NONE) {
        background(style.background)
    } else {
        drawWithCache {
            val image = calligraphyTexture(size.width.toInt(), size.height.toInt(), style.background)
            onDrawBehind { drawImage(image) }
        }
    }

/**
 * Textura de papel de caligrafía (estilo 화선지 teñido de negro): motas finas y fibras cortas
 * apenas más claras que la base. Semilla fija para que la página no "cambie" al redibujar.
 */
private fun calligraphyTexture(width: Int, height: Int, base: Color): ImageBitmap {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(base.toArgb())
    val rnd = Random(7L)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    val speckles = (w.toLong() * h / 700).toInt()
    repeat(speckles) {
        val light = rnd.nextFloat() < 0.7f
        paint.color = if (light) android.graphics.Color.argb(8 + rnd.nextInt(14), 235, 222, 196)
        else android.graphics.Color.argb(20 + rnd.nextInt(20), 0, 0, 0)
        canvas.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 0.5f + rnd.nextFloat() * 1.3f, paint)
    }

    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    val fibers = (w.toLong() * h / 5000).toInt()
    val path = Path()
    repeat(fibers) {
        val x = rnd.nextFloat() * w
        val y = rnd.nextFloat() * h
        val angle = rnd.nextFloat() * Math.PI.toFloat() * 2f
        val length = 12f + rnd.nextFloat() * 50f
        val bend = (rnd.nextFloat() - 0.5f) * length * 0.6f
        val ex = x + cos(angle) * length
        val ey = y + sin(angle) * length
        path.reset()
        path.moveTo(x, y)
        path.quadTo((x + ex) / 2f - sin(angle) * bend, (y + ey) / 2f + cos(angle) * bend, ex, ey)
        paint.strokeWidth = 0.5f + rnd.nextFloat() * 0.8f
        paint.color = android.graphics.Color.argb(18 + rnd.nextInt(26), 120, 100, 72)
        canvas.drawPath(path, paint)
    }
    return bmp.asImageBitmap()
}
