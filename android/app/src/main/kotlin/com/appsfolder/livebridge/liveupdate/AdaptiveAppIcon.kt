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
import android.util.LruCache
import kotlin.math.roundToInt

/**
 * Builds a clean, FULL-BLEED, fully OPAQUE square bitmap from an app's own launcher icon, to be
 * used as the notification's large icon.
 *
 * It stays an ordinary Bitmap on purpose: One UI accepts it exactly like the bitmap LiveBridge
 * always sent, so the app icon keeps showing in notifications and the Now Bar. (A previous
 * attempt handed over an "adaptive bitmap" Icon; One UI ignored it and fell back to LiveBridge's
 * own icon.)
 *
 * What changes compared with the old bitmap:
 *  - it is drawn from the app's raw icon layers, so One UI's icon framing (white tile, shrunk
 *    icon, squircle with transparent corners) is not baked into it;
 *  - it has no transparent or half-transparent pixels, so no white fringe can show through;
 *  - it is a plain square: the system itself rounds it (notification) or crops it (Now Bar circle).
 *
 * Only the app's own icon is converted. Avatars, album art, navigation arrows and every other
 * source bitmap are left exactly as they were, and on ANY failure this returns null so the caller
 * keeps the old behaviour.
 */
internal object AdaptiveAppIcon {
    private const val SIZE_DP = 80f
    private const val MIN_SIZE_PX = 192
    private const val MAX_SIZE_PX = 384

    // An adaptive icon is a 108dp canvas of which only the centre 72dp is visible.
    // Drawing the layers 1.5x larger than the bitmap, centred, shows exactly that window.
    private const val LAYER_OVERSCAN = 0.25f

    // Legacy (non-adaptive) icons are drawn at this share of the square.
    private const val LEGACY_ICON_FRACTION = 0.80f

    private val bitmaps = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * Returns a full-bleed opaque bitmap ONLY when [chosen] is the app's own icon bitmap
     * (the one AppMetadataCache hands out). For anything else it returns null.
     */
    fun forLargeIcon(context: Context, packageName: String, chosen: Bitmap?): Bitmap? {
        if (chosen == null) return null
        return try {
            val appIcon = AppMetadataCache.get(context, packageName).largeIcon ?: return null
            if (chosen !== appIcon && !chosen.sameAs(appIcon)) return null
            iconBitmap(context, packageName)
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    private fun iconBitmap(context: Context, packageName: String): Bitmap? {
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
            val background = drawable.background
            val foreground = drawable.foreground
            if (background == null && foreground == null) return null
            // Opaque base first, so nothing underneath can ever be transparent.
            canvas.drawColor(Color.WHITE)
            val overscan = (size * LAYER_OVERSCAN).roundToInt()
            background?.let { drawLayer(canvas, it, size, overscan) }
            foreground?.let { drawLayer(canvas, it, size, overscan) }
        } else {
            // Legacy (non-adaptive) icon: centre it on a backdrop taken from its own edge.
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
        }
        // Every pixel is opaque: say so, so the system never treats it as a see-through icon.
        bitmap.setHasAlpha(false)
        return bitmap
    }

    private fun drawLayer(canvas: Canvas, layer: Drawable, size: Int, overscan: Int) {
        layer.setBounds(-overscan, -overscan, size + overscan, size + overscan)
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
