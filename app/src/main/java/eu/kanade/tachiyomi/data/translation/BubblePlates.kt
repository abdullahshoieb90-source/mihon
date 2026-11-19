package eu.kanade.tachiyomi.data.translation

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import tachiyomi.domain.translation.model.BubblePlate
import tachiyomi.domain.translation.model.BubblePlateDetector
import tachiyomi.domain.translation.model.PageBrightness
import tachiyomi.domain.translation.model.PageColor

/**
 * Reads a block's bubble out of the page thumbnail.
 *
 * The thumbnail is what the text layer already samples to pick a plate colour, so nothing extra is decoded: the same
 * few hundred readings say where the bubble ends and what colour it is. [previewScale] is the thumbnail's scale
 * against the page, and [box] is in page coordinates.
 *
 * Returns null when the thumbnail can't be read (a hardware bitmap, or text that has no bubble around it), and the
 * layer then draws the plain plate it always drew.
 */
fun detectBubblePlate(preview: Bitmap?, previewScale: Float, box: Rect): BubblePlate? {
    if (preview == null || previewScale <= 0f) return null
    if (preview.config == Bitmap.Config.HARDWARE) return null

    val brightness = PageBrightness { x, y ->
        val pixel = preview.getPixel(x, y)
        (Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f) / 255f
    }
    val color = PageColor { x, y -> preview.getPixel(x, y) }

    return BubblePlateDetector.detect(
        textLeft = box.left * previewScale,
        textTop = box.top * previewScale,
        textRight = box.right * previewScale,
        textBottom = box.bottom * previewScale,
        width = preview.width,
        height = preview.height,
        brightness = brightness,
        color = color,
    )?.scaledBy(1f / previewScale)
}

/** The plate back in page coordinates, which is what gets saved and drawn. */
private fun BubblePlate.scaledBy(factor: Float): BubblePlate = copy(
    left = left * factor,
    top = top * factor,
    right = right * factor,
    bottom = bottom * factor,
    cornerRadius = cornerRadius * factor,
)
