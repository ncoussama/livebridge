package com.appsfolder.livebridge.liveupdate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.util.LruCache
import kotlin.math.roundToInt

/**
 * Builds a full-bleed ADAPTIVE large icon from an app's own launcher icon.
 *
 * Why: the large icon used to be a plain, already-masked bitmap (rounded corners + transparent
 * pixels). One UI treats such a bitmap as a "legacy" icon: it shrinks it and puts it on a white
 * tile, and in the Now Bar the transparent/antialiased edge lets white pixels show through.
 * An adaptive icon is masked by the system itself, so it fills the whole slot.
 *
 * Only the app's own icon is converted. Avatars, album art, navigation arrows and every other
 * source bitmap are left exactly as they were, and on ANY failure this returns null so the caller
 * keeps the old behaviour.
 */
internal object AdaptiveAppIcon {
    // The bitmap stands for the 108dp adaptive canvas; the system shows its centre 72dp.
    private const val SIZE_DP = 96f
    private const val MIN_SIZE_PX = 192
    private const val MAX_SIZE_PX = 432
    private const val LEGACY_ICON_FRACTION = 0.62f

    private val bitmaps = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * Returns an adaptive [Icon] ONLY when [chosen] is the app's own icon bitmap
     * (the one AppMetadataCache hands out). For anything else it returns null.
     */
    fun forLargeIcon(context: Context, packageName: String, chosen: Bitmap?): Icon? {
        if (chosen == null) return null
        return try {
            val appIcon = AppMetadataCache.get(context, packageName).largeIcon ?: return null
            if (chosen !== appIcon && !chosen.sameAs(appIcon)) return null
            val bitmap = adaptiveBitmap(context, packageName) ?: return null
            Icon.createWithAdaptiveBitmap(bitmap)
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    private fun adaptiveBitmap(context: Context, packageName: String): Bitmap? {
        val appContext = context.applicationContext
        val size = (SIZE_DP * appContext.resources.displayMetrics.density)
            .roundToInt()
            .coerceIn(MIN_SIZE_PX, MAX_SIZE_PX)
        val updatedAt = try {
            appContext.packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        } catch (_: Exception) {
            0L
        }
        val key = "$packageName|$size|$updatedAt|${appContext.resources.configuration.uiMode}"
        bitmaps.get(key)?.let { return it }
        val built = render(appContext, packageName, size) ?: return null
        bitmaps.put(key, built)
        return built
    }

    private fun render(context: Context, packageName: String, size: Int): Bitmap? {
        val drawable = loadLauncherDrawable(context, packageName) ?: return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (drawable is AdaptiveIconDrawable) {
            // Draw the raw 108dp layers (no mask) so the system can mask them itself.
            val background = drawable.background
            val foreground = drawable.foreground
            if (background == null && foreground == null) return null
            background?.let { drawLayer(canvas, it, size) }
            foreground?.let { drawLayer(canvas, it, size) }
            return bitmap
        }

        // Legacy (non-adaptive) icon: put it in the visible window on a backdrop taken from its edge.
        val legacy = drawableToBitmap(drawable) ?: return null
        canvas.drawColor(edgeColor(legacy))
        val side = size * LEGACY_ICON_FRACTION
        val offset = (size - side) / 2f
        canvas.drawBitmap(
            legacy,
            null,
            RectF(offset, offset, offset + side, offset + side),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return bitmap
    }

    private fun drawLayer(canvas: Canvas, layer: Drawable, size: Int) {
        layer.setBounds(0, 0, size, size)
        layer.draw(canvas)
    }

    /**
     * The app's own launcher icon, loaded straight from the app's resources so OEM icon theming
     * (pre-masked squircle bitmaps returned by PackageManager) does not get in the way.
     */
    private fun loadLauncherDrawable(context: Context, packageName: String): Drawable? {
        val pm = context.packageManager
        try {
            val info = pm.getApplicationInfo(packageName, 0)
            if (info.icon != 0) {
                val packageContext = context.createPackageContext(packageName, 0)
                val raw = packageContext.resources.getDrawableForDensity(
                    info.icon,
                    context.resources.configuration.densityDpi,
                    packageContext.theme
                )
                if (raw != null) return raw
            }
        } catch (_: Exception) {
        }
        return try {
            pm.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) {
            val existing = drawable.bitmap
            if (existing != null) {
                return if (existing.config == Bitmap.Config.HARDWARE) {
                    existing.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    existing
                }
            }
        }
        val width = drawable.intrinsicWidth.let { if (it > 0) it else 192 }.coerceAtMost(512)
        val height = drawable.intrinsicHeight.let { if (it > 0) it else 192 }.coerceAtMost(512)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    /** Average colour of the opaque pixels along the icon's border; white if the border is mostly empty. */
    private fun edgeColor(source: Bitmap): Int {
        val grid = 24
        val small = Bitmap.createScaledBitmap(source, grid, grid, true)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (y in 0 until grid) {
            for (x in 0 until grid) {
                val onRing = x < 4 || y < 4 || x >= grid - 4 || y >= grid - 4
                if (!onRing) continue
                val pixel = small.getPixel(x, y)
                if (Color.alpha(pixel) < 230) continue
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count += 1
            }
        }
        if (count < grid) return Color.WHITE
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }
}
