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

    // Minimum time between alerts of the same kind to prevent spamming
    private val REPEAT_COOLDOWN_MS = TimeUnit.SECONDS.toMillis(30)
    // Distance threshold to trigger the alert (meters)
    private val ALERT_DISTANCE_M = 80.0

    fun setControls(newControls: List<TrafficControl>) {
        controls = newControls
        alertedIds.clear()
    }

    /**
     * Check current location and return the kind of alert to trigger, or null if none.
     */
    fun check(currentLoc: LatLng): TrafficControl.Kind? {
        val now = System.currentTimeMillis()
        
        // Find the nearest control that we haven't alerted for recently
        val nearest = controls.filter { control ->
            val id = "${control.kind}_${control.loc.lat}_${control.loc.lng}"
            !alertedIds.contains(id)
        }.map { it to it.loc.distanceTo(currentLoc) }
        .filter { it.second < ALERT_DISTANCE_M }
        .minByOrNull { it.second }

        return nearest?.let { (control, distance) ->
            val id = "${control.kind}_${control.loc.lat}_${control.loc.lng}"
            
            // Extra cooldown for the same type of alert globally
            if (now - lastAlertTime < 5000) return null 

            alertedIds.add(id)
            lastAlertTime = now
            control.kind
        }
    }

    fun clear() {
        controls = emptyList()
        alertedIds.clear()
    }
}
