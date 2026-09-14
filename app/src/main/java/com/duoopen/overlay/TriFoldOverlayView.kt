package com.duoopen.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import com.duoopen.fold.TriFoldLine
import com.duoopen.fold.TriShader
import com.duoopen.settings.DuoConfig
import com.duoopen.settings.DuoSettings
import kotlin.math.ceil

/**
 * Full-screen overlay that draws a frozen screenshot through the three-pane
 * tri-fold shader. Mirrors [FoldOverlayView] but tracks two independent pane
 * tilts (left from H1, right from H2); the center pane is rendered crisp by the
 * shader.
 *
 * As in the book variant the shader view is laid out at 1/[renderScale] size,
 * rendered into its own hardware layer and scaled back up; the frost hides the
 * upscale, and when both tilts are ~0 the plain snapshot is drawn 1:1 instead.
 */
class TriFoldOverlayView(
    context: Context,
    snapshot: Bitmap,
    /** Tri-fold geometry; null = thirds from the config. */
    private val foldLine: ((w: Float, h: Float, config: DuoConfig) -> TriFoldLine)? = null,
    private val renderScale: Float = 2f,
) : ViewGroup(context) {

    private val fold = TriFoldView(context, snapshot, renderScale, foldLine)
    private val flat = FlatView(context, snapshot)

    var tiltLeft: Float
        get() = fold.tiltLeft
        set(value) {
            fold.tiltLeft = value
            updateVisibility()
        }

    var tiltRight: Float
        get() = fold.tiltRight
        set(value) {
            fold.tiltRight = value
            updateVisibility()
        }

    var config: DuoConfig
        get() = fold.config
        set(value) {
            fold.config = value
        }

    init {
        setBackgroundColor(Color.BLACK)
        addView(flat)
        addView(fold)
        fold.pivotX = 0f
        fold.pivotY = 0f
        fold.scaleX = renderScale
        fold.scaleY = renderScale
        fold.setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun updateVisibility() {
        val useFold = fold.tiltLeft >= TriShader.FLAT_EPSILON || fold.tiltRight >= TriShader.FLAT_EPSILON
        fold.visibility = if (useFold) VISIBLE else INVISIBLE
        flat.visibility = if (useFold) INVISIBLE else VISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        flat.measure(exactly(w), exactly(h))
        fold.measure(exactly(ceil(w / renderScale).toInt()), exactly(ceil(h / renderScale).toInt()))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        flat.layout(0, 0, r - l, b - t)
        fold.layout(0, 0, fold.measuredWidth, fold.measuredHeight)
    }

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY)

    /** Snapshot drawn 1:1 — pixel-identical to the live screen underneath. */
    private class FlatView(context: Context, private val snapshot: Bitmap) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val shader = BitmapShader(snapshot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val matrix = Matrix()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            matrix.setScale(w / snapshot.width.toFloat(), h / snapshot.height.toFloat())
            shader.setLocalMatrix(matrix)
            paint.shader = shader
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    /** Snapshot through the tri-fold shader, at reduced resolution. */
    private class TriFoldView(
        context: Context,
        private val snapshot: Bitmap,
        renderScale: Float,
        private val foldLine: ((w: Float, h: Float, config: DuoConfig) -> TriFoldLine)?,
    ) : View(context) {
        private val shader: RuntimeShader? = TriShader.create(context)
        private val pxPerMm = TriShader.pxPerMm(context) / renderScale
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val image = BitmapShader(snapshot, Shader.TileMode.DECAL, Shader.TileMode.DECAL)
        private val matrix = Matrix()

        var config: DuoConfig = DuoSettings.config.value

        var tiltLeft = 0f
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }
        var tiltRight = 0f
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            matrix.setScale(w / snapshot.width.toFloat(), h / snapshot.height.toFloat())
            image.setLocalMatrix(matrix)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1f || h <= 1f) return
            canvas.drawColor(Color.BLACK)
            val fold = shader
            if (fold == null) {
                paint.shader = image
            } else {
                val line = foldLine?.invoke(w, h, config)
                    ?: TriShader.triFold(w, h, config.foldSplitsLong)
                TriShader.setUniforms(fold, w, h, tiltLeft, tiltRight, config, pxPerMm, line)
                fold.setInputShader("content", image)
                paint.shader = fold
            }
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }
}
