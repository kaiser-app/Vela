package app.vela.core.nav

import app.vela.core.data.TrafficControl
import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import java.util.concurrent.TimeUnit

/**
 * Monitors the distance to traffic controls (stop signs, crossings, etc.) during navigation
 * and triggers voice/UI alerts when approaching them.
 */
class NavAlertEngine {
    private var controls: List<TrafficControl> = emptyList()
    private val alertedIds = mutableSetOf<String>()
    private var lastAlertTime = 0L

    /** UI-controlled: mirrors the "Traffic sign & light alerts" setting. When false, [check]
     *  always returns null without touching [alertedIds] — a re-enable mid-drive should resume
     *  finding controls normally, not skip everything already "seen" while it was off. */
    var alertsEnabled: Boolean = true

    // Minimum time between alerts of the same kind to prevent spamming
    private val REPEAT_COOLDOWN_MS = TimeUnit.SECONDS.toMillis(45)
    // Distance threshold to trigger the alert (meters). 120m is better for city driving.
    private val ALERT_DISTANCE_M = 120.0

    fun setControls(newControls: List<TrafficControl>) {
        controls = newControls
        alertedIds.clear()
        // TEMP DEBUG (traffic-control chain investigation, 2026-08-20): confirms the alert engine
        // actually RECEIVED a non-empty set from the fetch pipeline. If this logs count=0 every
        // drive, the break is upstream (Overpass fetch/filter), not here.
        android.util.Log.d("VelaTrafficAlert", "setControls count=${newControls.size} kinds=${newControls.groupingBy { it.kind }.eachCount()}")
    }

    /**
     * Check current location and return the kind of alert to trigger, or null if none.
     */
    fun check(currentLoc: LatLng): TrafficControl.Kind? {
        if (!alertsEnabled) return null
        val now = System.currentTimeMillis()

        // Find the nearest control that we haven't alerted for recently
        val nearest = controls.filter { control ->
            val id = "${control.kind}_${control.loc.lat}_${control.loc.lng}"
            !alertedIds.contains(id)
        }.map { it to it.loc.distanceTo(currentLoc) }
        .filter { it.second < ALERT_DISTANCE_M }
        .minByOrNull { it.second }

        // TEMP DEBUG: the exact "candidate kind / candidate distance" the ticket asked for. Fires on
        // every location update while a not-yet-alerted control is within ALERT_DISTANCE_M, whether
        // or not it ends up actually triggering (cooldown can still veto it below).
        if (nearest != null) {
            android.util.Log.d("VelaTrafficAlert", "candidate kind=${nearest.first.kind} distanceM=${"%.1f".format(nearest.second)}")
        }

        return nearest?.let { (control, distance) ->
            val id = "${control.kind}_${control.loc.lat}_${control.loc.lng}"

            // Extra cooldown for the same type of alert globally
            if (now - lastAlertTime < 5000) return null

            alertedIds.add(id)
            lastAlertTime = now
            android.util.Log.d("VelaTrafficAlert", "trigger emitted kind=${control.kind} distanceM=${"%.1f".format(distance)}")
            control.kind
        }
    }

    fun clear() {
        controls = emptyList()
        alertedIds.clear()
    }
}
