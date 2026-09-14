package com.duoopen.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import com.duoopen.fold.DualHingeSource
import com.duoopen.fold.DuoShader
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.TiltFollower
import com.duoopen.fold.TriShader
import com.duoopen.fold.isInnerPanel
import com.duoopen.settings.DuoSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * System-wide fold effect. Accessibility services may screenshot the display
 * and draw above every other window, which is what lets the fold cover the
 * launcher, lock screen and apps — not just the wallpaper.
 *
 * Opening: the inner panel comes up → one screenshot (retried if the panel
 * was still black) → a full-screen, touch-transparent overlay draws it
 * through the fold shader, tracking the hinge → removed once flat.
 * Closing: the hinge leaves flat → screenshot → same overlay, removed when
 * the panel switches to the cover screen.
 *
 * The snapshot is frozen for the fraction of a second of a fold, which is
 * invisible in practice; if the hinge stops partway (tent mode) the overlay
 * fades out instead so live content isn't hidden.
 */
class FoldOverlayService : AccessibilityService() {

    private enum class Phase { IDLE, CAPTURING, SHOWING }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private lateinit var hinge: HingeAngleSource
    private lateinit var displayManager: DisplayManager
    private var windowManager: WindowManager? = null

    private var phase = Phase.IDLE
    private var overlay: FoldOverlayView? = null
    private var follower: TiltFollower? = null

    /** Which panel is live; the effect restarts whenever this flips mid-fold. */
    private var innerPanel = false
    /** Set at a rest pose (closed on the cover, flat on the inner) so leaving it plays once. */
    private var restArmed = true
    private var panelSwitched = false
    private var lastHingeMoveMs = 0L
    private var demoRunning = false
    /** Bumped per capture so a late or hung screenshot can't act on a newer phase. */
    private var captureGen = 0
    /** Overlay is resolving on a timer, ignoring the hinge (see [show]). */
    private var timedResolve = false

    // --- Tri-fold (two-hinge / three-pane) state ---
    /** True when the device exposes two hinge sensors (Samsung Galaxy Z TriFold). */
    private var triMode = false
    private var dualHinge: DualHingeSource? = null
    /** Last open/closed state seen from the binary fallback hinge sensor; null until the first event. */
    private var fallbackOpen: Boolean? = null
    private var triView: TriFoldOverlayView? = null
    private var followerLeft: TiltFollower? = null
    private var followerRight: TiltFollower? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) evaluate()
        }
    }

    private val settleCheck = object : Runnable {
        override fun run() {
            val o = overlay ?: return
            if (o.tilt < DuoShader.FLAT_EPSILON) return
            if (!demoRunning && SystemClock.uptimeMillis() - lastHingeMoveMs >= SETTLE_TIMEOUT_MS) {
                dismiss(fadeMs = FADE_OUT_STALLED_MS)
            } else {
                handler.postDelayed(this, 100)
            }
        }
    }

    /** Tri-fold stall guard: if neither hinge moved for the timeout and the fold
     * isn't flat, fade out so a parked half-fold doesn't hide the live screen. */
    private val triSettleCheck = object : Runnable {
        override fun run() {
            val v = triView ?: return
            if (TriShader.bothFlat(dualHinge?.lastAngleLeft ?: Float.NaN, dualHinge?.lastAngleRight ?: Float.NaN)) return
            if (!demoRunning && SystemClock.uptimeMillis() - lastHingeMoveMs >= SETTLE_TIMEOUT_TRI_MS) {
                dismissTri(fadeMs = FADE_OUT_STALLED_MS)
            } else {
                handler.postDelayed(this, 150)
            }
        }
    }

    /** `adb shell am broadcast -a com.duoopen.DEMO` plays the effect over whatever is on screen. */
    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = playDemo()
    }
    private var receiverRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (!receiverRegistered) {
            registerReceiver(demoReceiver, IntentFilter(ACTION_DEMO), RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, handler)
        // Detect a tri-fold (two hinge sensors) before starting sensors. On a
        // book-style foldable only one hinge is found, so the original path runs.
        val tri = DualHingeSource(this) { left, right -> onHingesTri(left, right) }
        if (tri.isTriFold) {
            triMode = true
            dualHinge = tri
            tri.start()
            Log.i(TAG, "tri-fold mode; hinges=${tri.sensorNames}")
        } else {
            hinge = HingeAngleSource(this) { onHinge(it) }
            hinge.start()
            innerPanel = defaultDisplay().isInnerPanel()
            Log.i(TAG, "book mode; hinge=${hinge.sensor?.name} inner=$innerPanel")
        }
    }

    override fun onDestroy() {
        instance = null
        if (receiverRegistered) unregisterReceiver(demoReceiver)
        if (triMode) dualHinge?.stop() else hinge.stop()
        displayManager.unregisterDisplayListener(displayListener)
        removeOverlay()
        removeTriOverlay()
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun defaultDisplay(): Display? = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    private fun currentTilt(): Float =
        DuoShader.tiltFor(hinge.lastAngle, DuoSettings.config.value, innerPanel)

    private fun onHinge(angle: Float) {
        lastHingeMoveMs = SystemClock.uptimeMillis()
        evaluate()
        val tilt = DuoShader.tiltFor(angle, DuoSettings.config.value, innerPanel)
        if (timedResolve) return
        if (tilt < DuoShader.FLAT_EPSILON && phase == Phase.SHOWING && !demoRunning) {
            // At rest: drop the overlay now rather than easing the last degrees.
            dismiss(fadeMs = FADE_OUT_FLAT_MS)
        } else {
            follower?.setTarget(tilt)
        }
    }

    /**
     * Drives the effect from two signals: the live panel and the hinge angle.
     * Whichever panel is up, the picture is flat at its rest pose (cover
     * closed, inner open) and fully frosted at the panel swap, so an open or a
     * close is one continuous frost-up on the first panel and frost-down on
     * the second. A capture starts on leaving rest and again on each swap.
     */
    private fun evaluate() {
        if (demoRunning || triMode) return
        val inner = defaultDisplay().isInnerPanel()
        if (inner != innerPanel) {
            innerPanel = inner
            panelSwitched = true
            if (phase != Phase.IDLE) removeOverlay() // old panel's snapshot is meaningless now
        }
        val angle = hinge.lastAngle
        if (angle.isNaN()) return
        val tilt = DuoShader.tiltFor(angle, DuoSettings.config.value, inner)
        if (tilt < DuoShader.FLAT_EPSILON) {
            restArmed = true
            panelSwitched = false
            return
        }
        if (phase != Phase.IDLE) return
        when {
            panelSwitched -> {
                panelSwitched = false
                restArmed = false
                Log.i(TAG, "panel swapped (inner=$inner) at hinge=$angle")
                // The fresh panel may still be lighting up: retry if black.
                startCapture(afterSwap = true)
            }
            restArmed && tilt >= REST_LEAVE_TILT -> {
                restArmed = false
                Log.i(TAG, "leaving rest (inner=$inner) at hinge=$angle")
                startCapture(afterSwap = false)
            }
        }
    }

    private fun startCapture(afterSwap: Boolean, startTilt: Float? = null) {
        phase = Phase.CAPTURING
        capture(gen = ++captureGen, attempt = 1, afterSwap = afterSwap, startTilt = startTilt)
    }

    private fun capture(gen: Int, attempt: Int, afterSwap: Boolean, startTilt: Float?) {
        val t0 = SystemClock.uptimeMillis()
        fun stale() = gen != captureGen || phase != Phase.CAPTURING
        // The framework refuses captures closer than ~333 ms apart, measured
        // from the previous request — so a slow capture costs no extra wait.
        fun retry() {
            val wait = (t0 + SCREENSHOT_MIN_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            handler.postDelayed({ if (!stale()) capture(gen, attempt + 1, afterSwap, startTilt) }, wait)
        }
        // A screenshot requested as a panel switches off may never call back.
        handler.postDelayed({
            if (!stale()) {
                Log.w(TAG, "capture $attempt timed out; giving up")
                phase = Phase.IDLE
                demoRunning = false
            }
        }, CAPTURE_TIMEOUT_MS)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                buffer.close()
                if (stale()) {
                    Log.i(TAG, "stale capture after ${SystemClock.uptimeMillis() - t0}ms; dropped")
                    bitmap?.recycle()
                    return
                }
                if (bitmap == null) {
                    Log.w(TAG, "screenshot buffer could not be wrapped")
                    phase = Phase.IDLE
                    return
                }
                if (!afterSwap || attempt >= MAX_CAPTURE_ATTEMPTS) {
                    onCaptured(bitmap, afterSwap, startTilt, t0)
                    return
                }
                scope.launch {
                    val black = withContext(Dispatchers.Default) { isMostlyBlack(bitmap) }
                    if (stale()) {
                        bitmap.recycle()
                        return@launch
                    }
                    if (black && !demoRunning) {
                        bitmap.recycle()
                        Log.i(TAG, "capture $attempt is black after ${SystemClock.uptimeMillis() - t0}ms; retrying")
                        retry()
                    } else {
                        onCaptured(bitmap, afterSwap, startTilt, t0)
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                if (stale()) return
                // Secure content (banking, DRM video) and rate limits land here.
                Log.w(TAG, "screenshot failed: $errorCode")
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < MAX_CAPTURE_ATTEMPTS) {
                    retry()
                } else {
                    phase = Phase.IDLE
                    demoRunning = false
                }
            }
        })
    }

    private fun onCaptured(bitmap: Bitmap, afterSwap: Boolean, startTilt: Float?, t0: Long) {
        if (phase != Phase.CAPTURING) {
            bitmap.recycle()
            return
        }
        val angle = hinge.lastAngle
        val tilt = startTilt ?: currentTilt()
        val nearlyDone = afterSwap && startTilt == null &&
            if (innerPanel) angle > SKIP_INNER_ABOVE_HINGE else angle < SKIP_COVER_BELOW_HINGE
        if (tilt < DuoShader.FLAT_EPSILON || nearlyDone) {
            // Too late to be worth a pop-in: the fold is (almost) over.
            Log.i(TAG, "hinge=$angle by capture time (${SystemClock.uptimeMillis() - t0}ms); skipping")
            bitmap.recycle()
            phase = Phase.IDLE
            return
        }
        // Closing onto the cover: the hinge HAL goes quiet around 30°, so the
        // overlay would never hear "closed". Resolve on a timer instead — by
        // the time this capture lands the phone is shut anyway, so it reads
        // as the cover settling into focus.
        val timed = afterSwap && !innerPanel && startTilt == null
        Log.i(TAG, "showing ${bitmap.width}x${bitmap.height} at tilt=$tilt (capture ${SystemClock.uptimeMillis() - t0}ms)${if (timed) " timed" else ""}")
        show(bitmap, tilt, fadeIn = afterSwap, timed = timed)
    }

    /** Samples a coarse grid; true when nothing on screen is brighter than near-black. */
    private fun isMostlyBlack(hw: Bitmap): Boolean {
        val sw = runCatching { hw.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return false
        try {
            val n = 24
            var maxSum = 0
            for (iy in 0 until n) {
                val y = ((iy + 0.5f) * sw.height / n).toInt()
                for (ix in 0 until n) {
                    val x = ((ix + 0.5f) * sw.width / n).toInt()
                    val c = sw.getPixel(x, y)
                    val sum = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                    if (sum > maxSum) maxSum = sum
                }
            }
            return maxSum < BLACK_THRESHOLD
        } finally {
            sw.recycle()
        }
    }

    private fun show(bitmap: Bitmap, startTilt: Float, fadeIn: Boolean = false, timed: Boolean = false) {
        val display = defaultDisplay() ?: run { bitmap.recycle(); phase = Phase.IDLE; return }
        val wm = windowManager ?: createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
            .also { windowManager = it }

        val inner = innerPanel
        val view = FoldOverlayView(this, bitmap, { w, h, c -> DuoShader.foldFor(inner, w, h, c) }).apply {
            config = DuoSettings.config.value
            tilt = startTilt
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "DuoOpenFold"
        }
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            bitmap.recycle()
            phase = Phase.IDLE
            return
        }
        overlay = view
        phase = Phase.SHOWING
        OverlayState.setRunning(true)
        if (fadeIn) {
            // Content was already live on this panel; ease the frost in.
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }
        follower = TiltFollower { t ->
            view.tilt = t
            if (t < DuoShader.FLAT_EPSILON && !demoRunning) dismiss(fadeMs = FADE_OUT_FLAT_MS)
        }.also { it.snap(startTilt) }
        lastHingeMoveMs = SystemClock.uptimeMillis()
        timedResolve = timed
        if (timed) {
            follower?.tauS = TIMED_RESOLVE_TAU_S
            follower?.setTarget(0f)
        } else {
            handler.postDelayed(settleCheck, SETTLE_TIMEOUT_MS)
        }
    }

    private fun dismiss(fadeMs: Long) {
        val view = overlay ?: return
        Log.i(TAG, "dismiss (fade ${fadeMs}ms) at tilt=${view.tilt}")
        handler.removeCallbacks(settleCheck)
        follower?.cancel()
        follower = null
        overlay = null
        timedResolve = false
        phase = Phase.IDLE
        view.animate()
            .alpha(0f)
            .setDuration(fadeMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { detach(view) }
            .start()
    }

    private fun removeOverlay() {
        phase = Phase.IDLE
        timedResolve = false
        val view = overlay ?: return
        handler.removeCallbacks(settleCheck)
        follower?.cancel()
        follower = null
        overlay = null
        detach(view)
    }

    private fun detach(view: FoldOverlayView) {
        runCatching { windowManager?.removeViewImmediate(view) }
        OverlayState.setRunning(false)
    }

    /**
     * Manual check without folding: snapshot the screen and play what this
     * panel shows during a fold. Inner panel: an unfold from full frost to
     * flat. Cover panel: frost sweeping in (opening) then back out (closing).
     */
    fun playDemo(durationMs: Long = 1400) {
        if (phase != Phase.IDLE || demoRunning) return
        if (triMode) { playDemoTri(durationMs); return }
        demoRunning = true
        val inner = innerPanel
        val peak = DuoShader.MAX_TILT * DuoSettings.config.value.intensity.coerceAtMost(1f)
        // Frost at the very start so the overlay is visibly there; on the
        // cover it starts flat and sweeps in first.
        startCapture(afterSwap = false, startTilt = if (inner) peak else 0.06f)
        handler.postDelayed({
            val f = follower
            if (f == null) {
                demoRunning = false
                return@postDelayed
            }
            // Slow ease so the demo reads as a fold rather than a snap.
            f.tauS = durationMs / 4000f
            if (inner) {
                f.setTarget(0f)
                handler.postDelayed({
                    demoRunning = false
                    dismiss(fadeMs = FADE_OUT_FLAT_MS)
                }, durationMs)
            } else {
                f.setTarget(peak)
                handler.postDelayed({ f.setTarget(0f) }, durationMs)
                handler.postDelayed({
                    demoRunning = false
                    dismiss(fadeMs = FADE_OUT_FLAT_MS)
                }, durationMs * 2)
            }
        }, 450)
    }

    // -------------------------------------------------------------------------
    // Tri-fold state machine (two hinges / three panes). The book path above is
    // untouched; these methods run only when [triMode] is true.
    // -------------------------------------------------------------------------

    /** Driven by both hinge sensors. Independent tilts; capture on the first
     *  hinge to leave 0°, dismiss only when both are flat. */
    private fun onHingesTri(left: Float, right: Float) {
        lastHingeMoveMs = SystemClock.uptimeMillis()
        if (dualHinge?.usingFallback == true) {
            onFallbackHingeTri(left)
            return
        }
        val config = DuoSettings.config.value
        val tiltL = TriShader.tiltForHinge(left, config)
        val tiltR = TriShader.tiltForHinge(right, config)
        when (phase) {
            Phase.IDLE -> {
                if (TriShader.bothFlat(left, right)) {
                    // Back to flat: arm so the next fold triggers the effect.
                    restArmed = true
                } else if (restArmed && (left > TriShader.OPEN_TRIGGER || right > TriShader.OPEN_TRIGGER)) {
                    restArmed = false
                    Log.i(TAG, "tri leaving rest; H1=$left H2=$right")
                    startCaptureTri()
                }
            }
            Phase.SHOWING -> {
                followerLeft?.setTarget(tiltL)
                followerRight?.setTarget(tiltR)
                if (TriShader.bothFlat(left, right) && !demoRunning) {
                    dismissTri(fadeMs = FADE_OUT_FLAT_MS)
                }
            }
            Phase.CAPTURING -> Unit
        }
    }

    /**
     * The public hinge_angle sensor on the Z TriFold is effectively binary: it
     * reports exactly 0 (fully closed) or 180 (fully open) and stays silent while
     * individual panels move. There is no angle to follow, so a closed→open flip
     * plays the scripted unfold curve instead.
     */
    private fun onFallbackHingeTri(angle: Float) {
        val open = angle >= TriShader.FLAT_HINGE_TRI
        val wasOpen = fallbackOpen
        fallbackOpen = open
        if (wasOpen == false && open && phase == Phase.IDLE && !demoRunning) {
            Log.i(TAG, "tri fallback unfold; playing scripted curve")
            playDemoTri()
        }
    }

    private fun startCaptureTri() {
        phase = Phase.CAPTURING
        captureTri(gen = ++captureGen, attempt = 1, startTiltLeft = null, startTiltRight = null)
    }

    private fun captureTri(gen: Int, attempt: Int, startTiltLeft: Float?, startTiltRight: Float?) {
        val t0 = SystemClock.uptimeMillis()
        fun stale() = gen != captureGen || phase != Phase.CAPTURING
        fun retry() {
            val wait = (t0 + SCREENSHOT_MIN_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            handler.postDelayed({
                if (!stale()) captureTri(gen, attempt + 1, startTiltLeft, startTiltRight)
            }, wait)
        }
        handler.postDelayed({
            if (!stale()) {
                Log.w(TAG, "tri capture $attempt timed out; giving up")
                phase = Phase.IDLE
                demoRunning = false
            }
        }, CAPTURE_TIMEOUT_MS)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                buffer.close()
                if (stale()) {
                    Log.i(TAG, "tri stale capture after ${SystemClock.uptimeMillis() - t0}ms; dropped")
                    bitmap?.recycle()
                    return
                }
                if (bitmap == null) {
                    Log.w(TAG, "tri screenshot buffer could not be wrapped")
                    phase = Phase.IDLE
                    return
                }
                if (attempt >= MAX_CAPTURE_ATTEMPTS) {
                    onCapturedTri(bitmap, t0, startTiltLeft, startTiltRight)
                    return
                }
                scope.launch {
                    val black = withContext(Dispatchers.Default) { isMostlyBlack(bitmap) }
                    if (stale()) {
                        bitmap.recycle()
                        return@launch
                    }
                    if (black && !demoRunning) {
                        bitmap.recycle()
                        Log.i(TAG, "tri capture $attempt is black after ${SystemClock.uptimeMillis() - t0}ms; retrying")
                        retry()
                    } else {
                        onCapturedTri(bitmap, t0, startTiltLeft, startTiltRight)
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                if (stale()) return
                Log.w(TAG, "tri screenshot failed: $errorCode")
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < MAX_CAPTURE_ATTEMPTS) {
                    retry()
                } else {
                    phase = Phase.IDLE
                    demoRunning = false
                }
            }
        })
    }

    private fun onCapturedTri(bitmap: Bitmap, t0: Long, startTiltLeft: Float?, startTiltRight: Float?) {
        if (phase != Phase.CAPTURING) {
            bitmap.recycle()
            return
        }
        val config = DuoSettings.config.value
        val dh = dualHinge
        val liveL = TriShader.tiltForHinge(dh?.lastAngleLeft ?: Float.NaN, config)
        val liveR = TriShader.tiltForHinge(dh?.lastAngleRight ?: Float.NaN, config)
        val tiltL = startTiltLeft ?: liveL
        val tiltR = startTiltRight ?: liveR
        if (tiltL < TriShader.FLAT_EPSILON && tiltR < TriShader.FLAT_EPSILON) {
            Log.i(TAG, "tri both near flat by capture time (${SystemClock.uptimeMillis() - t0}ms); skipping")
            bitmap.recycle()
            phase = Phase.IDLE
            return
        }
        Log.i(TAG, "tri showing ${bitmap.width}x${bitmap.height} L=$tiltL R=$tiltR (${SystemClock.uptimeMillis() - t0}ms)")
        showTri(bitmap, tiltL, tiltR)
    }

    private fun showTri(bitmap: Bitmap, startTiltLeft: Float, startTiltRight: Float) {
        val display = defaultDisplay() ?: run { bitmap.recycle(); phase = Phase.IDLE; return }
        val wm = windowManager ?: createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
            .also { windowManager = it }

        val view = TriFoldOverlayView(this, bitmap).apply {
            config = DuoSettings.config.value
            tiltLeft = startTiltLeft
            tiltRight = startTiltRight
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "DuoOpenTriFold"
        }
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "tri addView failed", e)
            bitmap.recycle()
            phase = Phase.IDLE
            return
        }
        triView = view
        phase = Phase.SHOWING
        OverlayState.setRunning(true)
        // Both tilts ease independently; either reaching flat alone isn't enough —
        // the dismiss check fires from onHingesTri once both are flat.
        followerLeft = TiltFollower { t -> view.tiltLeft = t }.also { it.snap(startTiltLeft) }
        followerRight = TiltFollower { t -> view.tiltRight = t }.also { it.snap(startTiltRight) }
        lastHingeMoveMs = SystemClock.uptimeMillis()
        handler.postDelayed(triSettleCheck, SETTLE_TIMEOUT_TRI_MS)
    }

    private fun dismissTri(fadeMs: Long) {
        val view = triView ?: return
        Log.i(TAG, "tri dismiss (fade ${fadeMs}ms)")
        handler.removeCallbacks(triSettleCheck)
        followerLeft?.cancel()
        followerRight?.cancel()
        followerLeft = null
        followerRight = null
        triView = null
        phase = Phase.IDLE
        restArmed = true
        view.animate()
            .alpha(0f)
            .setDuration(fadeMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { detachTri(view) }
            .start()
    }

    private fun removeTriOverlay() {
        phase = Phase.IDLE
        val view = triView ?: return
        handler.removeCallbacks(triSettleCheck)
        followerLeft?.cancel()
        followerRight?.cancel()
        followerLeft = null
        followerRight = null
        triView = null
        detachTri(view)
    }

    private fun detachTri(view: TriFoldOverlayView) {
        runCatching { windowManager?.removeViewImmediate(view) }
        OverlayState.setRunning(false)
    }

    /** Tri demo: both panes start frosted, right (H2) clears first then left (H1). */
    private fun playDemoTri(durationMs: Long = 1800) {
        demoRunning = true
        val peak = TriShader.MAX_TILT * DuoSettings.config.value.intensity.coerceAtMost(1f)
        phase = Phase.CAPTURING
        captureTri(gen = ++captureGen, attempt = MAX_CAPTURE_ATTEMPTS, startTiltLeft = peak, startTiltRight = peak)
        handler.postDelayed({
            val fl = followerLeft
            val fr = followerRight
            if (fl == null || fr == null) {
                demoRunning = false
                return@postDelayed
            }
            fl.tauS = durationMs / 4000f
            fr.tauS = durationMs / 4000f
            // H2 (right) opens first, then H1 (left) — matching the tri-fold unfold order.
            fr.setTarget(0f)
            handler.postDelayed({ fl.setTarget(0f) }, (durationMs * 0.45f).toLong())
            handler.postDelayed({
                demoRunning = false
                dismissTri(fadeMs = FADE_OUT_FLAT_MS)
            }, durationMs)
        }, 450)
    }

    companion object {
        private const val TAG = "DuoOverlay"
        const val ACTION_DEMO = "com.duoopen.DEMO"
        /** The framework rejects screenshots closer together than ~333 ms. */
        private const val SCREENSHOT_MIN_INTERVAL_MS = 340L
        private const val MAX_CAPTURE_ATTEMPTS = 3
        private const val CAPTURE_TIMEOUT_MS = 800L
        private const val BLACK_THRESHOLD = 30
        /** Ease time constant for the timed resolve (≈ 250 ms to settle). */
        private const val TIMED_RESOLVE_TAU_S = 0.07f
        /** Tilt hysteresis for leaving a rest pose, so hinge jitter doesn't fire. */
        private const val REST_LEAVE_TILT = 3f
        /** After a swap, don't bother if the fold is nearly finished by capture time. */
        private const val SKIP_INNER_ABOVE_HINGE = 135f
        private const val SKIP_COVER_BELOW_HINGE = 10f
        private const val SETTLE_TIMEOUT_MS = 700L
        /** Tri-fold stall timeout: longer than book's — the tri-fold has stable mid-states. */
        private const val SETTLE_TIMEOUT_TRI_MS = 1200L
        private const val FADE_IN_MS = 140L
        private const val FADE_OUT_FLAT_MS = 120L
        private const val FADE_OUT_STALLED_MS = 300L

        /** The connected service, for in-process control from the app. */
        @Volatile
        var instance: FoldOverlayService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val self = ComponentName(context, FoldOverlayService::class.java)
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == self }
        }
    }
}
