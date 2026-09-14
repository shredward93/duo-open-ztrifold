package com.duoopen.fold

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Reports a tri-fold's two hinge angles independently: left (H1) and right (H2),
 * both in degrees (0 = closed, 180 = flat).
 *
 * `SensorManager.getDefaultSensor(TYPE_HINGE_ANGLE)` returns a single hinge even
 * on a two-hinge device, so the full sensor list is enumerated. Samsung's Z TriFold
 * exposes its two physical hinges as proprietary "Folding Angle" and "Folding
 * Angle INNER" sensors (com.samsung.sensor.folding_angle / _sub), but those
 * require `com.samsung.permission.SSENSOR` (signature|privileged) — a sideloaded
 * app cannot access them. When that permission is denied at registration time,
 * this source falls back to the standard `android.sensor.hinge_angle` sensor and
 * drives both panes from that single fused angle (both frost/clear in sync).
 *
 * Hinge angle sensors are on-change, so registering delivers the current angle
 * immediately and then only fires while a hinge actually moves.
 */
class DualHingeSource(
    context: Context,
    private val onHinges: (left: Float, right: Float) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    /**
     * All hinge/fold-angle sensors found on the device. Matches the standard
     * `hinge_angle` type and Samsung's proprietary `folding_angle` /
     * `folding_angle_sub` sensors. State sensors (folding_state,
     * folding_state_lpm) are excluded — they report posture, not angles.
     */
    private val allHinges: List<Sensor> = sensorManager?.getSensorList(Sensor.TYPE_ALL)
        ?.filter { s ->
            val t = s.stringType.lowercase()
            val n = s.name.lowercase()
            (t.contains("hinge") || n.contains("hinge") ||
                t.contains("folding_angle") || n.contains("folding angle")) &&
                !t.contains("folding_state") && !t.contains("folding_state_lpm")
        }
        // Prefer Samsung proprietary folding_angle sensors over the standard
        // hinge_angle (which is a single fused value on a tri-fold).
        ?.sortedByDescending { it.stringType.lowercase().contains("folding_angle") }
        ?: emptyList()

    /** True when at least two distinct hinge sensors were discovered (device is a tri-fold). */
    val isTriFold: Boolean = allHinges.size >= 2

    /** The standard Android hinge_angle sensor, used as fallback when Samsung sensors are denied. */
    private val fallbackSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, false)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

    /** True when the Samsung proprietary sensors were denied and we fell back to the standard sensor. */
    var usingFallback = false
        private set

    val sensorLeft: Sensor? get() = if (usingFallback) fallbackSensor else hinges.firstOrNull()
    val sensorRight: Sensor? get() = if (usingFallback) fallbackSensor else hinges.getOrNull(1)
    val sensorNames: List<String> get() = allHinges.map { it.name }

    private var hinges: List<Sensor> = assignHinges()

    var lastAngleLeft: Float = Float.NaN
        private set
    var lastAngleRight: Float = Float.NaN
        private set

    private var started = false

    /**
     * Maps the discovered hinge sensors to (H1 left, H2 right). Recognition:
     * - Samsung "Folding Angle INNER" / "folding_angle_sub" → H1 (left, inner
     *   hinge, opens second during an unfold since the right panel folds on
     *   top of the left when closed).
     * - Samsung "Folding Angle" / "folding_angle" → H2 (right, outer, opens
     *   first).
     * - Generic: "left"/"h1"/"1" → left, "right"/"h2"/"2" → right.
     * Falls back to enumeration order and warns if names don't help.
     */
    private fun assignHinges(): List<Sensor> {
        if (!isTriFold) return allHinges
        val byName = mutableMapOf<Int, Sensor>()
        for (s in allHinges) {
            val n = s.name.lowercase()
            val t = s.stringType.lowercase()
            when {
                // Samsung TriFold: INNER = inner/left hinge (H1)
                n.contains("inner") || t.contains("folding_angle_sub") ||
                    n.contains("left") || n.contains("h1") -> byName[LEFT_KEY] = s
                // Samsung TriFold: non-INNER "Folding Angle" = outer/right hinge (H2)
                n.contains("folding angle") && !n.contains("inner") ||
                    t.contains("folding_angle") && !t.contains("sub") ||
                    n.contains("right") || n.contains("h2") -> byName[RIGHT_KEY] = s
                n.contains("1") && !n.contains("2") -> byName[LEFT_KEY] = s
                n.contains("2") && !n.contains("1") -> byName[RIGHT_KEY] = s
            }
        }
        val left = byName[LEFT_KEY]
        val right = byName[RIGHT_KEY]
        if (left != null && right != null && left !== right) return listOf(left, right)
        // Fall back to enumeration order, preferring the two we may have matched.
        val ordered = allHinges.filter { it !== byName[LEFT_KEY] && it !== byName[RIGHT_KEY] }
        val picked = mutableListOf<Sensor>()
        byName[LEFT_KEY]?.let { picked.add(it) }
        byName[RIGHT_KEY]?.let { picked.add(it) }
        for (s in ordered) {
            if (picked.size >= 2) break
            if (s !in picked) picked.add(s)
        }
        Log.w(TAG, "hinge L/R could not be inferred from names; using $picked by order")
        return picked
    }

    fun start() {
        if (started) return
        val sm = sensorManager ?: return
        started = true

        // Try the Samsung proprietary sensors first. registerListener returns
        // false when the permission (com.samsung.permission.SSENSOR) is denied.
        val toRegister = hinges.take(2)
        var registered = 0
        for (s in toRegister) {
            if (sm.registerListener(this, s, SAMPLING_PERIOD_US)) registered++
            else Log.w(TAG, "registerListener denied for ${s.name} — likely SSENSOR permission")
        }

        if (registered == 0 && isTriFold && fallbackSensor != null) {
            // Both Samsung sensors were denied. Fall back to the standard
            // hinge_angle sensor and drive both panes from it in sync.
            usingFallback = true
            val ok = sm.registerListener(this, fallbackSensor, SAMPLING_PERIOD_US)
            Log.i(TAG, "Samsung sensors denied; fallback to ${fallbackSensor.name} (ok=$ok)")
        }

        Log.i(
            TAG,
            "tri=$isTriFold fallback=$usingFallback left=${sensorLeft?.name} right=${sensorRight?.name} " +
                "all=${allHinges.map { "${it.name}[${it.stringType}]" }}",
        )
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val angle = event.values.firstOrNull() ?: return
        val sensor = event.sensor
        Log.d(TAG, "event: ${sensor.name}=$angle")
        if (usingFallback) {
            // Single fused angle drives both panes in sync.
            lastAngleLeft = angle
            lastAngleRight = angle
            onHinges(angle, angle)
        } else when (sensor) {
            sensorLeft -> {
                lastAngleLeft = angle
                onHinges(angle, lastAngleRight)
            }
            sensorRight -> {
                lastAngleRight = angle
                onHinges(lastAngleLeft, angle)
            }
            else -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        private const val TAG = "DuoHinge"
        private const val LEFT_KEY = -1
        private const val RIGHT_KEY = 1
        /** ~125 Hz ceiling; hinge sensors report only on change anyway. */
        private const val SAMPLING_PERIOD_US = 8_000
    }
}
