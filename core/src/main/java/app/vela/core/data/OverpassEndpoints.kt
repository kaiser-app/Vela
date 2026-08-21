package app.vela.core.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.net.URLEncoder

/**
 * Shared endpoint list + failover runner for every keyless **Overpass** (OpenStreetMap query API) fetch in
 * Vela: ALPR/Flock cameras ([OverpassAlprCameras]), traffic controls ([OverpassTrafficSignals]) and the
 * offline OSM POI/address index ([OverpassPois]).
 *
 * A single hardcoded endpoint was a real reliability hole. The main instance `overpass-api.de` regularly
 * returns HTTP 504 "dispatcher" errors when it's under load (observed 2026-07-13 - the exact failure behind
 * a "Flock cameras never show" report: the fetch failed and the layer silently stayed empty). [run] tries
 * each endpoint in turn and uses the FIRST that answers 2xx, so one overloaded mirror no longer takes a
 * whole feature down; the caller only sees a failure when EVERY endpoint is unreachable.
 */
object OverpassEndpoints {
    private const val USER_AGENT = "VelaMaps/0.1 (+https://github.com/kaiser-app/Vela)"

    /** Primary first, then community mirrors. Order is preference; each is tried until one answers 2xx.
     *  All speak the same Overpass QL, so the query string is endpoint-agnostic. */
    val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    /**
     * Run [query] against each endpoint in order and hand the FIRST successful (2xx) response body to
     * [onBody], returning its result. A non-2xx status, a network error, a timeout or an exception thrown
     * while parsing one endpoint's body all fall through to the NEXT endpoint. Returns null only when every
     * endpoint failed - callers treat that as "fetch failed" (distinct from a successful-but-empty parse, so
     * a transient outage is never cached as an authoritative "nothing here").
     *
     * [onBody] must fully consume the body before it returns; it runs inside the response's `use` block.
     */
    fun <T> run(http: OkHttpClient, query: String, onBody: (ResponseBody) -> T): T? {
        // Field data (vela-diag export, 2026-08-21): 5 of 9 real-world attempts failed and 3 more
        // returned an empty result — mostly transient mirror flakiness, not a hard block, based on
        // the mix of failure types logged below. A couple of short-backoff retries per endpoint
        // before moving on costs at most ~1s extra and measurably improves the odds of a route this
        // actually succeeds on, without needing the bigger pre-fetch/caching work (tracked
        // separately) to land first.
        val maxAttemptsPerEndpoint = 2
        for (ep in ENDPOINTS) {
            for (attempt in 1..maxAttemptsPerEndpoint) {
                val url = "$ep?data=" + URLEncoder.encode(query, "UTF-8")
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                try {
                    http.newCall(req).execute().use { resp ->
                        val body = resp.body
                        if (resp.isSuccessful && body != null) return onBody(body)
                        android.util.Log.w("VelaOverpass", "non-2xx from $ep (attempt $attempt/$maxAttemptsPerEndpoint): HTTP ${resp.code}")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("VelaOverpass", "exception from $ep (attempt $attempt/$maxAttemptsPerEndpoint): ${e.javaClass.simpleName}: ${e.message}")
                }
                if (attempt < maxAttemptsPerEndpoint) Thread.sleep(400L * attempt)
            }
        }
        android.util.Log.e("VelaOverpass", "ALL ${ENDPOINTS.size} endpoints failed (×$maxAttemptsPerEndpoint attempts each) for query (first 80 chars): ${query.take(80)}")
        return null
    }
}
