package app.vela.core.data.traffic

import app.vela.core.data.CalibrationNeededException
import app.vela.core.model.LatLng
import app.vela.core.model.TrafficSpan
import app.vela.core.model.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * FRISSÍTETT TERV — a repó tényleges kódja alapján (lásd
 * `GoogleMapsDataSource.applyTraffic` és `docs/CALIBRATION.md`).
 *
 * Kiderült, hogy Google NEM az elsődleges router — az OSRM az
 * (`RouteGeometry`), Google csak két dolgot ad hozzá az OSRM-útvonalra:
 *   - `durationInTrafficSeconds` (élő, forgalommal korrigált ETA)
 *   - `trafficSpans` (szegmensenkénti torlódás-színezés)
 * ...és tartalék router, ha az OSRM elérhetetlen (az a külön ág, `googleDirections`).
 *
 * Ez a fájl ezért NEM egy párhuzamos routing-rendszer (ahogy a korábbi
 * `TrafficDataProvider.kt` vázolta), hanem egy szűkebb, célzott csere-pont:
 * ugyanazt a kis "overlay" adatot szolgáltatja, amit eddig kizárólag Google
 * adott — de láncban, HERE és TomTom tartalékkal.
 *
 * Integráció: a meglévő `applyTraffic(route: Route, g: Route?): Route`
 * hívásnál a paraméterül kapott `g` (eddig kizárólag a Google directions
 * eredménye) helyett ebből a láncból építünk egy vele kompatibilis, minimál
 * `TrafficOverlay?`-t. Az `applyTraffic` maga MÁR null-biztos (nincs Google
 * traffic → simán a szabad-áramlású OSRM route-ot adja vissza) — ez a
 * tulajdonság ide is öröklődik: ha mindhárom overlay-forrás hibázik, a route
 * változatlanul, forgalmi adat nélkül megy tovább, nem omlik össze semmi.
 */

/** Minimál, forrás-független forgalmi overlay — pont annyi, amennyit `applyTraffic` ténylegesen felhasznál. */
data class TrafficOverlay(
    val durationSeconds: Double,
    val durationInTrafficSeconds: Double,
    val distanceMeters: Double,
    val trafficSpans: List<TrafficSpan>,
    val source: TrafficOverlaySourceId,
)

enum class TrafficOverlaySourceId { GOOGLE, HERE, TOMTOM }

class TrafficOverlayException(val sourceId: TrafficOverlaySourceId, message: String, cause: Throwable? = null) :
    Exception(message, cause)

interface TrafficOverlaySource {
    val id: TrafficOverlaySourceId

    /** Best-effort: null, ha a forrás strukturálisan nem tud overlay-t adni erre az útvonalra
     *  (pl. rövid gyalogos szakasz). Hiba esetén dobjon [TrafficOverlayException]-t — SOHA ne adjon
     *  vissza csendben hibás/kitalált adatot. */
    suspend fun fetchOverlay(origin: LatLng, destination: LatLng, mode: TravelMode): TrafficOverlay?
}

// ---------------------------------------------------------------------------
// Circuit breaker + cache — változatlan logika a korábbi tervből, csak a
// szűkebb TrafficOverlay típusra alkalmazva.
// ---------------------------------------------------------------------------

class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val cooldown: java.time.Duration = java.time.Duration.ofMinutes(5),
) {
    private var consecutiveFailures = 0
    private var openedAt: Instant? = null

    fun isOpen(): Boolean {
        val opened = openedAt ?: return false
        if (Instant.now().isAfter(opened.plus(cooldown))) {
            reset()
            return false
        }
        return true
    }

    fun recordSuccess() = reset()

    fun recordFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) openedAt = Instant.now()
    }

    private fun reset() {
        consecutiveFailures = 0
        openedAt = null
    }
}

class TrafficOverlayCache(private val ttl: java.time.Duration = java.time.Duration.ofMinutes(2)) {
    private data class Entry(val overlay: TrafficOverlay?, val at: Instant)

    private val store = ConcurrentHashMap<String, Entry>()

    fun get(key: String): TrafficOverlay? {
        val entry = store[key] ?: return null
        if (ChronoUnit.SECONDS.between(entry.at, Instant.now()) > ttl.seconds) {
            store.remove(key)
            return null
        }
        return entry.overlay
    }

    fun put(key: String, overlay: TrafficOverlay?) {
        store[key] = Entry(overlay, Instant.now())
    }

    fun keyFor(origin: LatLng, destination: LatLng, mode: TravelMode): String =
        "${origin.lat},${origin.lng}->${destination.lat},${destination.lng}:$mode"
}

/**
 * A lánc-orchestrátor: Google → HERE → TomTom, kihagyva a "nyitott" circuit
 * breakerű forrásokat, 2 perces cache-elel. Ha minden forrás hibázik vagy
 * nincs is overlay (pl. Google `CalibrationNeededException`), null-t ad —
 * ezt a hívó (`applyTraffic`) már natívan tudja kezelni: az OSRM route
 * simán forgalmi adat nélkül megy tovább.
 */
class ChainedTrafficOverlaySource(
    private val sourcesInOrder: List<TrafficOverlaySource>,
    private val cache: TrafficOverlayCache = TrafficOverlayCache(),
    private val onSourceFailure: (TrafficOverlaySourceId, Throwable) -> Unit = { _, _ -> },
) {
    private val breakers = sourcesInOrder.associateWith { CircuitBreaker() }

    suspend fun getOverlay(origin: LatLng, destination: LatLng, mode: TravelMode): TrafficOverlay? =
        withContext(Dispatchers.IO) {
            val cacheKey = cache.keyFor(origin, destination, mode)
            cache.get(cacheKey)?.let { return@withContext it }

            for (src in sourcesInOrder) {
                val breaker = breakers.getValue(src)
                if (breaker.isOpen()) continue

                try {
                    val overlay = src.fetchOverlay(origin, destination, mode)
                    breaker.recordSuccess()
                    cache.put(cacheKey, overlay)
                    return@withContext overlay
                } catch (e: CalibrationNeededException) {
                    // Google-specifikus, de a lánc szempontjából ugyanaz, mint bármelyik hiba: megyünk tovább.
                    breaker.recordFailure()
                    onSourceFailure(src.id, e)
                } catch (e: Exception) {
                    breaker.recordFailure()
                    onSourceFailure(src.id, e)
                }
            }

            // Mindhárom forrás hibázott / nincs overlay: null — az applyTraffic() ezt már
            // natívan, biztonságosan kezeli (forgalmi adat nélküli OSRM route).
            cache.put(cacheKey, null)
            null
        }
}

// ---------------------------------------------------------------------------
// Források
// ---------------------------------------------------------------------------

/** 1. ELSŐDLEGES — a meglévő Google directions-hívás eredményét adaptálja erre a szűk interfészre.
 *  A tényleges scrape/parse logika NEM duplikálódik: ez csak egy vékony adapter a már létező
 *  `GoogleMapsDataSource` belső directions-hívása köré. */
class GoogleTrafficOverlaySource(
    private val googleDirections: suspend (LatLng, LatLng, TravelMode) -> RawGoogleDirectionsResult?,
) : TrafficOverlaySource {
    override val id = TrafficOverlaySourceId.GOOGLE

    override suspend fun fetchOverlay(origin: LatLng, destination: LatLng, mode: TravelMode): TrafficOverlay? {
        val g = try {
            googleDirections(origin, destination, mode)
        } catch (e: CalibrationNeededException) {
            throw e // a lánc kezeli, ne itt nyeljük el
        } catch (e: Exception) {
            throw TrafficOverlayException(id, "Google directions hívás sikertelen", e)
        } ?: return null

        val typical = g.durationSeconds.takeIf { it > 0 } ?: return null
        val inTraffic = g.durationInTrafficSeconds ?: return null

        return TrafficOverlay(
            durationSeconds = typical,
            durationInTrafficSeconds = inTraffic,
            distanceMeters = g.distanceMeters,
            trafficSpans = g.trafficSpans,
            source = id,
        )
    }
}

/** 2. FALLBACK — HERE Traffic API (hivatalos, ingyenes szinttel indul). */
class HereTrafficOverlaySource(
    private val apiKey: String,
    private val httpClient: TrafficHttpClient,
) : TrafficOverlaySource {
    override val id = TrafficOverlaySourceId.HERE

    override suspend fun fetchOverlay(origin: LatLng, destination: LatLng, mode: TravelMode): TrafficOverlay? {
        val raw = try {
            httpClient.get(buildHereUrl(origin, destination, mode, apiKey))
        } catch (e: Exception) {
            throw TrafficOverlayException(id, "HERE Traffic API hívás sikertelen", e)
        }
        return parseHereOverlay(raw)
    }

    private fun buildHereUrl(origin: LatLng, destination: LatLng, mode: TravelMode, key: String): String =
        TODO("HERE routing+traffic endpoint — regisztráció után a hivatalos sémával")

    private fun parseHereOverlay(raw: String): TrafficOverlay? =
        TODO("HERE JSON válasz -> TrafficOverlay, séma-validációval (analóg a CalibrationNeededException mintával)")
}

/** 3. UTOLSÓ FALLBACK — TomTom Traffic API. */
class TomTomTrafficOverlaySource(
    private val apiKey: String,
    private val httpClient: TrafficHttpClient,
) : TrafficOverlaySource {
    override val id = TrafficOverlaySourceId.TOMTOM

    override suspend fun fetchOverlay(origin: LatLng, destination: LatLng, mode: TravelMode): TrafficOverlay? {
        val raw = try {
            httpClient.get(buildTomTomUrl(origin, destination, mode, apiKey))
        } catch (e: Exception) {
            throw TrafficOverlayException(id, "TomTom Traffic API hívás sikertelen", e)
        }
        return parseTomTomOverlay(raw)
    }

    private fun buildTomTomUrl(origin: LatLng, destination: LatLng, mode: TravelMode, key: String): String =
        TODO("TomTom traffic endpoint — regisztráció után a hivatalos sémával")

    private fun parseTomTomOverlay(raw: String): TrafficOverlay? =
        TODO("TomTom JSON válasz -> TrafficOverlay")
}

interface TrafficHttpClient {
    suspend fun get(url: String): String
}

/** A meglévő Google directions belső eredményének azon szelete, amit az overlay-hez tényleg felhasználunk. */
data class RawGoogleDirectionsResult(
    val durationSeconds: Double,
    val durationInTrafficSeconds: Double?,
    val distanceMeters: Double,
    val trafficSpans: List<TrafficSpan>,
)

// ---------------------------------------------------------------------------
// Összeszerelés — CoreModule-ba illesztve (app.vela.core.di.CoreModule mintájára)
// ---------------------------------------------------------------------------
fun buildTrafficOverlaySource(
    googleDirections: suspend (LatLng, LatLng, TravelMode) -> RawGoogleDirectionsResult?,
    httpClient: TrafficHttpClient,
    hereApiKey: String,
    tomTomApiKey: String,
): ChainedTrafficOverlaySource =
    ChainedTrafficOverlaySource(
        sourcesInOrder = listOf(
            GoogleTrafficOverlaySource(googleDirections),
            HereTrafficOverlaySource(hereApiKey, httpClient),
            TomTomTrafficOverlaySource(tomTomApiKey, httpClient),
        ),
        onSourceFailure = { source, error -> AlertHooks.reportOverlayFailure(source, error) },
    )

object AlertHooks {
    fun reportOverlayFailure(source: TrafficOverlaySourceId, error: Throwable) {
        // TODO: Slack/email webhook — a Google-scraping törése percek/órák alatt kiderüljön.
    }
}
