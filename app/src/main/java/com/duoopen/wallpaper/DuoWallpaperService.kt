package com.duoopen.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.duoopen.fold.DualHingeSource
import com.duoopen.fold.DuoShader
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.TiltFollower
import com.duoopen.fold.TriShader
import com.duoopen.fold.isInnerPanel
import com.duoopen.overlay.OverlayState
import com.duoopen.settings.DuoConfig
import com.duoopen.settings.DuoSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live wallpaper that plays the Duo frosted-glass fold whenever the inner
 * display is partly folded: the wallpaper image sits on the flat plane, the
 * moving half of the screen acts as a glass pane hinged at the crease, and the
 * image settles into focus as the hinge reaches flat.
 *
 * Draws only on hinge movement / surface changes (no idle frames). On the
 * cover screen (tall, narrow) the image is drawn plain, and while the
 * full-screen overlay is running it steps aside so nothing is frosted twice.
 */
class DuoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DuoEngine()

    private inner class DuoEngine : Engine() {
        private val scope = MainScope()
        private val context = this@DuoWallpaperService
        private val hinge = HingeAngleSource(context, ::onHingeAngle)
        private val foldShader: RuntimeShader? = DuoShader.create(context)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val follower = TiltFollower { draw() }

        // Tri-fold (two-hinge / three-pane) path; active when [triMode] is true.
        private val dualHingeProbe = DualHingeSource(context) { left, right -> onHingesAngle(left, right) }
        private val triMode = dualHingeProbe.isTriFold
        private val triShader: RuntimeShader? = if (triMode) TriShader.create(context) else null
        private val followerLeft = TiltFollower { draw() }
        private val followerRight = TiltFollower { draw() }

        private var config: DuoConfig = DuoSettings.config.value
        private var bitmap: Bitmap? = null
        private var bitmapVersion = -1L
        private var imageShader: BitmapShader? = null
        private var pxPerMm = DuoShader.pxPerMm(context)

        private var surfaceReady = false
        private var width = 0f
        private var height = 0f

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false)
            displayContext?.let { pxPerMm = DuoShader.pxPerMm(it) }
            // Listen for the engine's whole life, not just while visible: the
            // OnePlus Open's hinge HAL sends nothing on registration, so a
            // listener started at unfold time would miss a fast open entirely.
            // It's on-change, so it's silent unless the hinge actually moves.
            if (triMode) dualHingeProbe.start() else hinge.start()
            scope.launch {
                DuoSettings.config.collect { c ->
                    config = c
                    if (c.imageVersion != bitmapVersion) loadImage(c.imageVersion)
                    if (triMode) {
                        followerLeft.snap(TriShader.tiltForHinge(dualHingeProbe.lastAngleLeft, c))
                        followerRight.snap(TriShader.tiltForHinge(dualHingeProbe.lastAngleRight, c))
                    } else {
                        follower.snap(tiltFor(hinge.lastAngle))
                    }
                    draw()
                }
            }
            scope.launch { OverlayState.running.collect { draw() } }
        }

        override fun onDestroy() {
            if (triMode) dualHingeProbe.stop() else hinge.stop()
            follower.cancel()
            followerLeft.cancel()
            followerRight.cancel()
            scope.cancel()
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                if (triMode) {
                    followerLeft.snap(followerLeft.target)
                    followerRight.snap(followerRight.target)
                } else {
                    follower.snap(follower.target)
                }
                draw()
            } else {
                follower.cancel()
                followerLeft.cancel()
                followerRight.cancel()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            surfaceReady = true
            width = w.toFloat()
            height = h.toFloat()
            displayContext?.let { pxPerMm = DuoShader.pxPerMm(it) }
            rebuildImageShader()
            // Folded -> unfolding swaps panels: start from the current hinge
            // pose instead of animating in from a stale value.
            if (triMode) {
                followerLeft.snap(followerLeft.target)
                followerRight.snap(followerRight.target)
            } else {
                follower.snap(follower.target)
            }
            draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            follower.cancel()
            followerLeft.cancel()
            followerRight.cancel()
            super.onSurfaceDestroyed(holder)
        }

        private fun onHingeAngle(angle: Float) {
            val tilt = tiltFor(angle)
            if (isInner() && isVisible && surfaceReady) follower.setTarget(tilt) else follower.snap(tilt)
        }

        private fun onHingesAngle(left: Float, right: Float) {
            val tl = TriShader.tiltForHinge(left, config)
            val tr = TriShader.tiltForHinge(right, config)
            if (isVisible && surfaceReady) {
                followerLeft.setTarget(tl)
                followerRight.setTarget(tr)
            } else {
                followerLeft.snap(tl)
                followerRight.snap(tr)
            }
        }

        private fun tiltFor(angle: Float): Float =
            if (angle.isNaN()) 0f else DuoShader.tiltForHinge(angle, config)

        private fun isInner(): Boolean = displayContext?.display.isInnerPanel()

        private fun loadImage(version: Long) {
            bitmapVersion = version
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { WallpaperImage.load(context, version) }
                if (version != bitmapVersion) return@launch
                bitmap = bmp
                rebuildImageShader()
                draw()
            }
        }

        private fun rebuildImageShader() {
            val bmp = bitmap ?: return
            if (width <= 0f || height <= 0f) return
            val (scale, dx, dy) = WallpaperImage.centerCrop(bmp.width, bmp.height, width, height)
            // DECAL: samples outside the image are transparent, so the frost
            // fades to black at the edges like the original instead of smearing.
            imageShader = BitmapShader(bmp, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply {
                filterMode = BitmapShader.FILTER_MODE_LINEAR
                setLocalMatrix(Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(dx, dy)
                })
            }
        }

        private fun draw() {
            if (!surfaceReady) return
            val holder = surfaceHolder
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: Exception) {
                Log.w(TAG, "lockHardwareCanvas failed", e)
                null
            } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                val image = imageShader ?: return
                if (triMode) {
                    drawTri(canvas, image)
                } else {
                    drawBook(canvas, image)
                }
                canvas.drawRect(0f, 0f, width, height, paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun drawBook(canvas: Canvas, image: BitmapShader) {
            val shader = foldShader
            val tilt = if (isInner() && !OverlayState.running.value) follower.current else 0f
            if (shader == null || tilt < DuoShader.FLAT_EPSILON) {
                paint.shader = image
            } else {
                val fold = DuoShader.centeredFold(width, height, config.foldSplitsLong)
                DuoShader.setUniforms(shader, width, height, tilt, config, pxPerMm, fold)
                shader.setInputShader("content", image)
                paint.shader = shader
            }
        }

        private fun drawTri(canvas: Canvas, image: BitmapShader) {
            val shader = triShader
            val overlayRunning = OverlayState.running.value
            val tl = if (overlayRunning) 0f else followerLeft.current
            val tr = if (overlayRunning) 0f else followerRight.current
            if (shader == null || (tl < TriShader.FLAT_EPSILON && tr < TriShader.FLAT_EPSILON)) {
                paint.shader = image
            } else {
                val fold = TriShader.triFold(width, height, config.foldSplitsLong)
                TriShader.setUniforms(shader, width, height, tl, tr, config, pxPerMm, fold)
                shader.setInputShader("content", image)
                paint.shader = shader
            }
        }
    }

    private companion object {
        const val TAG = "DuoWallpaper"
    }
}
