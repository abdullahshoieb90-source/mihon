package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import eu.kanade.tachiyomi.data.translation.TranslatedPage
import eu.kanade.tachiyomi.data.translation.TranslatedTextBlock
import eu.kanade.tachiyomi.data.translation.detectBubblePlate
import mihon.app.di.appGraph
import tachiyomi.domain.translation.model.BubblePlate
import tachiyomi.domain.translation.model.PlateShape
import tachiyomi.domain.translation.model.TranslatedBlockArea
import tachiyomi.domain.translation.model.TranslationPlateStyle
import tachiyomi.domain.translation.model.TranslationTextColor
import tachiyomi.domain.translation.model.indexAt
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a page's translation on top of the page itself, in the page's own coordinate space.
 *
 * Because it is a child of the page view, the text moves and scales with the art: panning, zooming and scrolling
 * can't leave the translation behind.
 */
class TranslationTextLayer(context: Context) : View(context) {

    var page: TranslatedPage? = null
        set(value) {
            field = value
            lightCache.clear()
            plateCache.clear()
            drawnRects.clear()
            drawnBlocks.clear()
            invalidate()
        }

    /**
     * What was last drawn where, in this view's own coordinates. A long tap on the page is answered from this, so the
     * reader can show what a bubble says without guessing where the bubbles are.
     */
    private val drawnRects = ArrayList<TranslatedBlockArea>()

    /** The blocks those areas belong to, in the same order. */
    private val drawnBlocks = ArrayList<TranslatedTextBlock>()

    /**
     * The translation under a point of this view, or null when the point is on the art instead of a bubble.
     *
     * Every caller passes the point already mapped into the page view: the readers scale their pages above it, so a
     * point measured here is the one the drawing used.
     *
     * The areas are widened by a few density points: a finger covers more than a pixel, so a tap that lands just
     * outside the lettering still means the bubble it touched.
     */
    fun blockAt(x: Float, y: Float): TranslatedTextBlock? {
        val index = drawnRects.indexAt(x, y, TOUCH_SLOP_DP * density)
        return if (index >= 0) drawnBlocks.getOrNull(index) else null
    }

    /**
     * Maps a point of the page bitmap into this view. Zoomed readers need it; fit-width readers can leave it null.
     */
    var sourceToView: ((Float, Float) -> PointF?)? = null

    private val density = resources.displayMetrics.density

    private val preferences = context.appGraph.translationPreferences

    private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val normalTypeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = boldTypeface
        textAlign = Paint.Align.CENTER
    }

    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    /** Sampling the page on every redraw makes zooming in the paged reader stutter. */
    private val lightCache = HashMap<TranslatedTextBlock, Boolean>()

    /**
     * The bubble each block sits in, read once per block from the page thumbnail.
     *
     * Kept here rather than in the cache file: a plate is a property of the art, not of the translation, so chapters
     * translated before this existed get the better shape too, without being translated again.
     */
    private val plateCache = HashMap<TranslatedTextBlock, BubblePlate?>()

    init {
        // Taps belong to the page underneath, so the reader still reacts normally.
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val page = page ?: return
        val bitmap = page.preview
        if (page.width <= 0 || page.height <= 0 || width <= 0 || height <= 0) return

        val scaleX = width.toFloat() / page.width
        val scaleY = height.toFloat() / page.height
        val mapper = sourceToView
        val margin = 3f * density

        val placed = page.blocks.mapNotNull { block ->
            val box = RectF()
            mapBlock(block, mapper, scaleX, scaleY, box)
            if (box.width() < 2f || box.height() < 2f) null else block to box
        }
        if (placed.isEmpty()) return

        // One letter size for the whole page: sizing every bubble on its own made neighbouring bubbles look like
        // different readers had pasted them in. The size comes from a line of the original lettering, so the
        // translation sits at the weight of the page instead of shrinking into unreadable small print.
        val lineHeights = placed
            .map { (block, box) -> box.height() / block.lines.coerceAtLeast(1) }
            .sorted()
        val reference = lineHeights[(lineHeights.size - 1) * 3 / 5]
        val scale = preferences.textScale.get() / 100f
        val baseTextSize = (reference * LINE_TO_TEXT_RATIO * scale)
            .coerceIn(MIN_TEXT_SIZE_DP * density, MAX_TEXT_SIZE_DP * density)

        drawnRects.clear()
        drawnBlocks.clear()
        placed.forEach { (block, box) ->
            drawBlock(
                canvas = canvas,
                block = block,
                preview = bitmap,
                previewScale = page.previewScale,
                blockRect = box,
                margin = margin,
                baseTextSize = baseTextSize,
                mapper = mapper,
                scaleX = scaleX,
                scaleY = scaleY,
            )
        }
    }

    private fun mapBlock(
        block: TranslatedTextBlock,
        mapper: ((Float, Float) -> PointF?)?,
        scaleX: Float,
        scaleY: Float,
        into: RectF,
    ) = mapRect(block.box, mapper, scaleX, scaleY, into)

    private fun mapRect(
        box: Rect,
        mapper: ((Float, Float) -> PointF?)?,
        scaleX: Float,
        scaleY: Float,
        into: RectF,
    ) {
        if (mapper != null) {
            val topLeft = mapper(box.left.toFloat(), box.top.toFloat())
            val bottomRight = mapper(box.right.toFloat(), box.bottom.toFloat())
            if (topLeft != null && bottomRight != null) {
                into.set(
                    min(topLeft.x, bottomRight.x),
                    min(topLeft.y, bottomRight.y),
                    max(topLeft.x, bottomRight.x),
                    max(topLeft.y, bottomRight.y),
                )
                return
            }
        }
        into.set(box.left * scaleX, box.top * scaleY, box.right * scaleX, box.bottom * scaleY)
    }

    private fun drawBlock(
        canvas: Canvas,
        block: TranslatedTextBlock,
        preview: Bitmap?,
        previewScale: Float,
        blockRect: RectF,
        margin: Float,
        baseTextSize: Float,
        mapper: ((Float, Float) -> PointF?)?,
        scaleX: Float,
        scaleY: Float,
    ) {
        val text = preferences.letterCase.get().apply(block.translated)
        if (text.isBlank()) return

        // Set before measuring: the layout has to be built with the typeface it will be drawn with.
        textPaint.typeface = if (preferences.boldText.get()) boldTypeface else normalTypeface

        // The bubble the lettering sits in, in this view's coordinates. Without one the block keeps the plain plate,
        // which is also what a page with no readable surroundings gets, and what the reader asked for by choosing the
        // plain shape.
        val style = preferences.plateStyle.get()
        val detectedPlate = if (style == TranslationPlateStyle.BOX) {
            null
        } else {
            plateCache.getOrPut(block) { detectBubblePlate(preview, previewScale, block.box) }
        }
        val plate = detectedPlate?.let { detected ->
            val rect = RectF()
            mapRect(
                Rect(
                    detected.left.toInt(),
                    detected.top.toInt(),
                    detected.right.toInt(),
                    detected.bottom.toInt(),
                ),
                mapper,
                scaleX,
                scaleY,
                rect,
            )
            DetectedPlate(detected, rect)
        }
        // The shape to paint: the bubble's own, or a rounded box over it when the reader prefers that.
        val target = plate?.rect ?: blockRect
        val shape = if (style == TranslationPlateStyle.ROUNDED) PlateShape.ROUNDED else plate?.detected?.shape
        val cornerRadius = when (shape) {
            null -> DEFAULT_CORNER_DP * density
            PlateShape.ROUNDED -> minOf(target.width(), target.height()) * ROUNDED_PLATE_CORNER
            PlateShape.RECT -> plate?.detected?.cornerRadius ?: DEFAULT_CORNER_DP * density
            PlateShape.STADIUM -> plate?.detected?.cornerRadius ?: (minOf(target.width(), target.height()) / 2f)
        }

        val chosenColor = TranslationTextColor.of(preferences.textColor.get())
        val plateIsLight = if (chosenColor != null) {
            !isLightColor(chosenColor)
        } else {
            plate?.let { !it.detected.dark }
                ?: lightCache.getOrPut(block) { isLightAround(preview, previewScale, block.box) }
        }

        // The colour the bubble actually is, so the plate blends into the art instead of flagging the page with a
        // white rectangle. A colour the reader chose always wins over it.
        val fill = chosenColor?.let { if (plateIsLight) Color.WHITE else Color.rgb(14, 14, 14) }
            ?: plate?.takeIf { Color.alpha(it.detected.color) == 0xFF }?.let { blend(it.detected.color, plateIsLight) }
            ?: if (plateIsLight) Color.WHITE else Color.rgb(14, 14, 14)

        textPaint.color = chosenColor ?: if (plateIsLight) Color.BLACK else Color.WHITE

        // An oval is only as wide as it is where the text sits: the plate is the shape, the text still has to fit the
        // bubble's middle, which is narrower than its bounding box on the corners.
        val isOval = shape == PlateShape.STADIUM
        val usableWidth = if (isOval) target.width() * OVAL_TEXT_WIDTH else target.width()
        val usableHeight = target.height()

        // Stay inside the bubble: a translation that spills over the art looks broken, so the text shrinks to fit the
        // original lettering area instead of the box growing around it.
        val textWidth = (usableWidth * MAX_WIDTH_GROWTH - margin * 2f).toInt().coerceAtLeast(1)

        var textSize = baseTextSize
        var layout = layout(text, textSize, textWidth)
        while (layout.height > usableHeight * MAX_GROWTH && textSize > MIN_TEXT_SIZE_DP * density) {
            textSize -= 0.5f * density
            layout = layout(text, textSize, textWidth)
        }

        val textHeight = layout.height.toFloat()
        val centerX = target.centerX()
        val centerY = target.centerY()

        // Kept for long taps: a bubble answers from the area its translation actually covers, not the art's box.
        drawnRects += TranslatedBlockArea(
            target.left,
            target.top,
            target.right,
            target.bottom,
        )
        drawnBlocks += block

        erasePaint.color = fill
        canvas.save()
        val path = platePath(target, cornerRadius, shape)
        canvas.clipPath(path)
        canvas.drawPath(path, erasePaint)

        canvas.translate(centerX, centerY - textHeight / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    /** A detected bubble, kept with its rectangle in this view's coordinates. */
    private class DetectedPlate(val detected: BubblePlate, val rect: RectF)

    /**
     * The outline to paint: an oval for a round bubble, a rounded box for a soft one, and a barely-rounded box for a
     * panel.
     */
    private fun platePath(rect: RectF, radius: Float, shape: PlateShape?): Path {
        val path = Path()
        when (shape) {
            PlateShape.STADIUM -> path.addOval(rect, Path.Direction.CW)
            null -> path.addRoundRect(rect, DEFAULT_CORNER_DP * density, DEFAULT_CORNER_DP * density, Path.Direction.CW)
            else -> path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        return path
    }

    /**
     * The bubble's own colour, kept if it stays readable: a plate that is too close to the lettering's colour turns
     * the text back into art. Anything that wouldn't read gets the plain plate instead.
     */
    private fun blend(color: Int, plateIsLight: Boolean): Int {
        val luma = Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114
        val readable = if (plateIsLight) luma >= READABLE_PLATE_LUMA else luma <= 255 - READABLE_PLATE_LUMA
        return if (readable) color else if (plateIsLight) Color.WHITE else Color.rgb(14, 14, 14)
    }

    /** True when the given colour is bright enough for a dark plate. */
    private fun isLightColor(color: Int): Boolean =
        Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114 >= LUMA_THRESHOLD

    /**
     * Average brightness of the page under the original text, so the box blends into bubbles and dark panels alike.
     * It reads the thumbnail, whose coordinates are the page's multiplied by [previewScale].
     */
    private fun isLightAround(preview: Bitmap?, previewScale: Float, box: Rect): Boolean {
        if (preview == null || preview.config == Bitmap.Config.HARDWARE) return true

        val left = (box.left * previewScale).toInt().coerceIn(0, preview.width - 1)
        val top = (box.top * previewScale).toInt().coerceIn(0, preview.height - 1)
        val right = (box.right * previewScale).toInt().coerceIn(left + 1, preview.width)
        val bottom = (box.bottom * previewScale).toInt().coerceIn(top + 1, preview.height)

        val stepX = max(1, (right - left) / 6)
        val stepY = max(1, (bottom - top) / 6)
        var sum = 0.0
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = preview.getPixel(x, y)
                sum += Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114
                count++
                x += stepX
            }
            y += stepY
        }
        return count == 0 || sum / count >= LUMA_THRESHOLD
    }

    private fun layout(text: String, textSize: Float, width: Int): StaticLayout {
        textPaint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.05f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    companion object {
        private const val TOUCH_SLOP_DP = 8f
        private const val MIN_TEXT_SIZE_DP = 9f
        private const val MAX_TEXT_SIZE_DP = 28f
        private const val LINE_TO_TEXT_RATIO = 0.72f
        private const val MAX_GROWTH = 1.5f
        private const val MAX_WIDTH_GROWTH = 1.12f
        private const val LUMA_THRESHOLD = 128.0
        private const val OVAL_TEXT_WIDTH = 0.82f
        private const val ROUNDED_PLATE_CORNER = 0.22f
        private const val DEFAULT_CORNER_DP = 2f

        /** A plate closer to the lettering's brightness than this is turned back into the plain one. */
        private const val READABLE_PLATE_LUMA = 96.0
    }
}
