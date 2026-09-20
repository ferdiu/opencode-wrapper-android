package it.ferdiu.opencodewrapper.auto

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat

/** Builds the colored project badges shown as row images, reproducing the
 *  OpenCode web UI's project avatar: rounded square + first letter, both
 *  tinted from the project's color key. [avatarColors] is pure JVM and unit
 *  tested; [projectIcon] is Android-only (bitmap drawing, untested). */
object ProjectIconFactory {

    private const val BADGE_SIZE_PX = 96
    private const val CORNER_RADIUS_PX = 18f
    private const val TEXT_SIZE_PX = 48f

    /** Bitmap badges are immutable per (label, colorKey) - cache them so
     *  onGetTemplate (and the blink refresh) never redraws. */
    private val cache = LruCache<Pair<String, String?>, CarIcon>(CACHE_SIZE)

    /** (background ARGB, foreground/text ARGB) for a project color key, dark-theme
     *  values matching the OpenCode web UI. Unknown/absent keys get a neutral gray. */
    fun avatarColors(colorKey: String?): Pair<Int, Int> = when (colorKey) {
        "pink" -> 0xFF501B3F.toInt() to 0xFFE34BA9.toInt()
        "mint" -> 0xFF033A34.toInt() to 0xFF95F3D9.toInt()
        "orange" -> 0xFF5F2A06.toInt() to 0xFFFF802B.toInt()
        "purple" -> 0xFF432155.toInt() to 0xFF9D5BD2.toInt()
        "cyan" -> 0xFF0F3058.toInt() to 0xFF369EFF.toInt()
        "lime" -> 0xFF2B3711.toInt() to 0xFFC4F042.toInt()
        else -> 0xFF2A2A2E.toInt() to 0xFFD4D4D8.toInt()
    }

    fun projectIcon(carContext: CarContext, label: String, colorKey: String?): CarIcon {
        val initial = label.ifBlank { "?" }.uppercase().first().toString()
        val key = initial to colorKey
        cache.get(key)?.let { return it }
        val (background, foreground) = avatarColors(colorKey)
        val bitmap = createBitmap(BADGE_SIZE_PX, BADGE_SIZE_PX)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = background
        canvas.drawRoundRect(
            RectF(0f, 0f, BADGE_SIZE_PX.toFloat(), BADGE_SIZE_PX.toFloat()),
            CORNER_RADIUS_PX, CORNER_RADIUS_PX, paint,
        )
        paint.color = foreground
        paint.textSize = TEXT_SIZE_PX
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        // Vertically center accounting for the font's ascent/descent.
        val baselineY = BADGE_SIZE_PX / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(initial, BADGE_SIZE_PX / 2f, baselineY, paint)
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
            .also { cache.put(key, it) }
    }

    private const val CACHE_SIZE = 64
}
