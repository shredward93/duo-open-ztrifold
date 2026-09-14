package com.duoopen.fold

import android.content.Context
import android.graphics.RuntimeShader
import android.util.Log
import com.duoopen.R
import com.duoopen.settings.DuoConfig

/** Hinge placement for a three-pane tri-fold: two vertical hinge lines. */
data class TriFoldLine(
    /** True when the hinges are vertical lines (split the x axis). */
    val splitsX: Boolean,
    /** Left hinge (H1) position in px along the split axis. */
    val hingePosLeft: Float,
    /** Right hinge (H2) position in px along the split axis. */
    val hingePosRight: Float,
    /** Eye position in px along the split axis (default the center-pane center). */
    val eyePos: Float = (hingePosLeft + hingePosRight) * 0.5f,
)

/** Shared glue for res/raw/tri_unfold.agsl — the three-pane tri-fold variant. */
object TriShader {
    /** Pane tilt cap; beyond this the kernel is mostly black anyway. */
    const val MAX_TILT = 45f

    /** Pane tilts below this draw the plain image (effect visually off). */
    const val FLAT_EPSILON = 0.05f

    /**
     * Hinge angle treated as fully flat on the tri-fold. Real hinge HALs rest
     * short of 180° (the original book app used 172°), so the overlay would
     * otherwise never dismiss. The TriFold's two hinges must both reach this
     * before the overlay drops.
     */
    const val FLAT_HINGE_TRI = 178f

    /** A closed tri-fold idles near 0°; leaving this fires the capture. */
    const val OPEN_TRIGGER = 1f

    private const val TAG = "TriShader"
    private const val REFERENCE_PX_PER_MM = 6f

    @Volatile
    private var source: String? = null

    fun create(context: Context): RuntimeShader? {
        val src = source ?: context.resources.openRawResource(R.raw.tri_unfold)
            .bufferedReader().use { it.readText() }
            .also { source = it }
        return try {
            RuntimeShader(src)
        } catch (e: Exception) {
            Log.e(TAG, "AGSL compile failed: ${e.message}", e)
            null
        }
    }

    /**
     * Side-pane tilt for one hinge. The visible hinge range [0, FLAT_HINGE_TRI]
     * maps linearly onto 0..[MAX_TILT] scaled by [DuoConfig.intensity], so a pane
     * is fully frosted when its hinge is shut and clears as it opens. Intensity
     * lets the frost linger further into the open.
     */
    fun tiltForHinge(hingeDegrees: Float, config: DuoConfig): Float {
        if (hingeDegrees.isNaN()) return 0f
        val progress = ((FLAT_HINGE_TRI - hingeDegrees) / FLAT_HINGE_TRI).coerceIn(0f, 1f)
        return (progress * MAX_TILT * config.intensity).coerceIn(0f, MAX_TILT)
    }

    /** True when both hinges have reached the flat tolerance. */
    fun bothFlat(left: Float, right: Float): Boolean {
        val tl = if (left.isNaN()) FLAT_HINGE_TRI else left
        val tr = if (right.isNaN()) FLAT_HINGE_TRI else right
        return tl >= FLAT_HINGE_TRI && tr >= FLAT_HINGE_TRI
    }

    /** Default tri-fold geometry: hinges at one-third and two-thirds of the split axis. */
    fun triFold(width: Float, height: Float, foldSplitsLong: Boolean): TriFoldLine {
        val splitsX = if (foldSplitsLong) width >= height else width < height
        val span = if (splitsX) width else height
        return TriFoldLine(
            splitsX = splitsX,
            hingePosLeft = span / 3f,
            hingePosRight = span * 2f / 3f,
        )
    }

    fun pxPerMm(context: Context): Float {
        val xdpi = context.resources.displayMetrics.xdpi
        return if (xdpi.isFinite() && xdpi > 0f) xdpi / 25.4f else REFERENCE_PX_PER_MM
    }

    fun setUniforms(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        tiltLeft: Float,
        tiltRight: Float,
        config: DuoConfig,
        pxPerMm: Float,
        fold: TriFoldLine,
    ) {
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("tiltLeft", tiltLeft)
        shader.setFloatUniform("tiltRight", tiltRight)
        shader.setFloatUniform("eyeDistancePx", config.eyeDistanceMm * pxPerMm)
        shader.setFloatUniform("hingePosLeft", fold.hingePosLeft)
        shader.setFloatUniform("hingePosRight", fold.hingePosRight)
        shader.setFloatUniform("eyeX", fold.eyePos)
        shader.setFloatUniform("axisSwap", if (fold.splitsX) 0f else 1f)
        shader.setFloatUniform("blurSpread", config.blurSpread)
        // Blur radius is in device px; renormalize the per-px darkening from the
        // original's ~6 px/mm so dense panels don't crush to black.
        shader.setFloatUniform("darkening", config.darkening * REFERENCE_PX_PER_MM / pxPerMm)
    }
}
