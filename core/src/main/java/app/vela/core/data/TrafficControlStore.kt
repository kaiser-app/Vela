package app.vela.core.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device cache for [TrafficControl]s (traffic lights, stop signs, crossings, ...), fetched
 * live from [OverpassTrafficSignals] and persisted here so a successful fetch survives past the
 * current drive — the next time a route touches an already-cached area, [near] can answer
 * instantly with no network call, and if a *live* fetch fails outright (field data: ~55% of real
 * attempts failed or came back empty — see OverpassEndpoints' retry commit), this is the fallback
 * instead of silence.
 *
 * Same SQLiteOpenHelper convention as [OfflinePoiStore] / [OfflineAddressStore] — a Room database
 * wasn't worth introducing for one small table pair.
 *
 * This is the "pre-download" step requested after the traffic-control reliability investigation
 * (2026-08-21): route-start still does a live fetch (so brand-new areas work), but every
 * successful fetch — live or, later, an explicit "download this region" action — makes the *next*
 * pass through the same area free and instant. A full region-bundle version of this (folded into
 * the existing offline routing-region downloads) is a bigger follow-up, not done here.
 */
@Singleton
class TrafficControlStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val helper = object : SQLiteOpenHelper(context, "vela_traffic_controls.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE control(id TEXT PRIMARY KEY, kind TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, fetchedAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX idx_control_latlng ON control(lat, lng)")
            // "Which grid cells have we actually asked Overpass about" — separate from the control
            // rows themselves, because a cell with zero signals is a valid, cacheable answer (an
            // empty result is not the same as "never checked"); without this table we'd re-fetch a
            // genuinely sign-free area forever since it would never accumulate any `control` rows.
            db.execSQL("CREATE TABLE coverage(cell TEXT PRIMARY KEY, fetchedAt INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            db.execSQL("DROP TABLE IF EXISTS control")
            db.execSQL("DROP TABLE IF EXISTS coverage")
            onCreate(db)
        }
    }

    // ~1.1km grid cell at the equator. Coarse enough that a typical route corridor only touches a
    // handful of cells (cheap coverage check); fine enough that one sampled point being fetched
    // doesn't get treated as covering a huge surrounding area it never actually queried.
    private val cellDeg = 0.01

    private fun cellOf(lat: Double, lng: Double): String =
        "${Math.floor(lat / cellDeg).toInt()}:${Math.floor(lng / cellDeg).toInt()}"

    /** Record a successful fetch: the controls found (possibly empty — that's still meaningful,
     *  see [coverage] above), AND which grid cells [sampledPoints] actually touched. */
    fun cacheResult(sampledPoints: List<LatLng>, controls: List<TrafficControl>) {
        if (sampledPoints.isEmpty()) return
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (c in controls) {
                val id = "${c.kind}_${"%.5f".format(c.loc.lat)}_${"%.5f".format(c.loc.lng)}"
                db.insertWithOnConflict(
                    "control", null,
                    ContentValues().apply {
                        put("id", id); put("kind", c.kind.name)
                        put("lat", c.loc.lat); put("lng", c.loc.lng)
                        put("fetchedAt", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            for (cell in sampledPoints.map { cellOf(it.lat, it.lng) }.toSet()) {
                db.insertWithOnConflict(
                    "coverage", null,
                    ContentValues().apply { put("cell", cell); put("fetchedAt", now) },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** True only if EVERY cell touched by [sampledPoints] has a coverage record within
     *  [maxAgeMs] — i.e. we have genuine full cached coverage of this corridor, not just a
     *  scattered few hits, so it's safe to skip the live fetch entirely. */
    fun hasFreshCoverage(sampledPoints: List<LatLng>, maxAgeMs: Long): Boolean {
        if (sampledPoints.isEmpty()) return false
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val db = helper.readableDatabase
        for (cell in sampledPoints.map { cellOf(it.lat, it.lng) }.toSet()) {
            val fresh = db.rawQuery(
                "SELECT 1 FROM coverage WHERE cell = ? AND fetchedAt >= ? LIMIT 1",
                arrayOf(cell, cutoff.toString()),
            ).use { it.moveToFirst() }
            if (!fresh) return false
        }
        return true
    }

    /** Cached controls within [radiusMeters] of any of [points]. Used both as the fast/offline
     *  path when [hasFreshCoverage] is true, and as a last-resort fallback (possibly stale,
     *  possibly partial) when a live fetch fails outright. */
    fun near(points: List<LatLng>, radiusMeters: Double): List<TrafficControl> {
        if (points.isEmpty()) return emptyList()
        val db = helper.readableDatabase
        val degPad = radiusMeters / 111_000.0
        val out = LinkedHashMap<String, TrafficControl>()
        for (p in points) {
            db.rawQuery(
                "SELECT id, kind, lat, lng FROM control WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?",
                arrayOf(
                    (p.lat - degPad).toString(), (p.lat + degPad).toString(),
                    (p.lng - degPad).toString(), (p.lng + degPad).toString(),
                ),
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (out.containsKey(id)) continue
                    val loc = LatLng(c.getDouble(2), c.getDouble(3))
                    if (loc.distanceTo(p) <= radiusMeters) {
                        val kind = runCatching { TrafficControl.Kind.valueOf(c.getString(1)) }.getOrNull() ?: continue
                        out[id] = TrafficControl(loc, kind)
                    }
                }
            }
        }
        return out.values.toList()
    }

    fun controlCount(): Int = helper.readableDatabase
        .rawQuery("SELECT COUNT(*) FROM control", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
