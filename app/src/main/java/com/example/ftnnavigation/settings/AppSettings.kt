package com.example.ftnnavigation.settings

import android.content.Context
import androidx.core.content.edit
import com.example.ftnnavigation.graph.RoutingProfile
import kotlin.math.roundToInt

/** Kako ruta menja sprat (Podešavanja): najbrže, ili izbegava stepenice / lift. */
enum class FloorChange(val profile: RoutingProfile) {
    /** Stepenice ili lift - šta je brže (lift: čekanje 45 s). */
    NAJBRZE(RoutingProfile()),

    /** Kolica, povreda, prtljag - liftom; gde lifta nema, stepenicama uz napomenu ([com.example.ftnnavigation.graph.Route.fallback]). */
    BEZ_STEPENICA(RoutingProfile(avoidStairs = true)),

    /** Samo stepenice. */
    BEZ_LIFTA(RoutingProfile(avoidLift = true)),
}

// Teren 02.10.2026: hodnik III sprata NTP-a (38,3 m) = 46 i 48 detektovanih koraka (01.10. 50) -> ~0,8 m. To je korak
// korisnika (visok); drugima je predug, pa se bira u Podešavanjima.
const val DEFAULT_STEP_LENGTH_M = 0.8f
const val MIN_STEP_LENGTH_M = 0.5f
const val MAX_STEP_LENGTH_M = 1.0f
const val STEP_LENGTH_INCREMENT_M = 0.05f

/** Dužina koraka u opsegu, zaokružena na [STEP_LENGTH_INCREMENT_M] (klizač vraća i međuvrednosti, npr. 0,7499). */
fun snapStepLength(meters: Float): Float {
    val increments = ((meters.coerceIn(MIN_STEP_LENGTH_M, MAX_STEP_LENGTH_M) - MIN_STEP_LENGTH_M) / STEP_LENGTH_INCREMENT_M).roundToInt()
    return ((MIN_STEP_LENGTH_M + increments * STEP_LENGTH_INCREMENT_M) * 100).roundToInt() / 100f
}

/** Podešavanja aplikacije sa ekrana Podešavanja (osim obaveštenja - ona su u `DepartureScheduler`). */
object AppSettings {
    private const val PREFS = "settings"
    private const val KEY_AUTO_ROTATE_MAP = "auto_rotate_map"
    private const val KEY_STAIR_TURNS = "stair_turns"
    private const val KEY_HEADING_ERRORS = "heading_errors"
    private const val KEY_FLOOR_CHANGE = "floor_change"
    private const val KEY_CROWD_ROUTING = "crowd_routing"
    private const val KEY_FAVORITES = "favorite_destinations"
    private const val KEY_RECENTS = "recent_destinations"
    private const val KEY_STEP_LENGTH = "step_length_m"

    /** Mapa se okreće za po 90° po smeru korisnika; podrazumevano isključeno. */
    fun autoRotateMap(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_ROTATE_MAP, false)

    fun setAutoRotateMap(context: Context, enabled: Boolean) = prefs(context).edit { putBoolean(KEY_AUTO_ROTATE_MAP, enabled) }

    /** Promena sprata na ruti (i za vreme polaska u obaveštenju); podrazumevano najbrže. */
    fun floorChange(context: Context): FloorChange =
        prefs(context).getString(KEY_FLOOR_CHANGE, null)?.let { name -> FloorChange.entries.find { it.name == name } }
            ?: FloorChange.NAJBRZE

    fun setFloorChange(context: Context, value: FloorChange) = prefs(context).edit { putString(KEY_FLOOR_CHANGE, value.name) }

    /** Koliko tačka pređe po koraku (PDR); podrazumevano [DEFAULT_STEP_LENGTH_M]. */
    fun stepLengthM(context: Context): Float = snapStepLength(prefs(context).getFloat(KEY_STEP_LENGTH, DEFAULT_STEP_LENGTH_M))

    fun setStepLengthM(context: Context, meters: Float) = prefs(context).edit { putFloat(KEY_STEP_LENGTH, snapStepLength(meters)) }

    /** Ruta računa gužvu između časova (procena iz rasporeda); podrazumevano uključeno. */
    fun crowdRouting(context: Context): Boolean = prefs(context).getBoolean(KEY_CROWD_ROUTING, true)

    fun setCrowdRouting(context: Context, enabled: Boolean) = prefs(context).edit { putBoolean(KEY_CROWD_ROUTING, enabled) }

    /**
     * Naučena strana okreta pri penjanju po stepeništu ("zgrada/stepenište" -> +1 u smeru kazaljke, −1 suprotno) - to je
     * osobina zgrade, pa se čuva i posle Reset-a. Zapis: "NTP/S1=1;KULA/S=-1".
     */
    fun stairTurns(context: Context): Map<String, Int> =
        prefs(context).getString(KEY_STAIR_TURNS, null).orEmpty().split(';').mapNotNull { entry ->
            val (key, value) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            value.toIntOrNull()?.let { key to it }
        }.toMap()

    fun setStairTurns(context: Context, turns: Map<String, Int>) =
        prefs(context).edit { putString(KEY_STAIR_TURNS, turns.entries.joinToString(";") { "${it.key}=${it.value}" }) }

    /**
     * Poslednje izmerene greške smera (stepeni, bez ispravke; prosek je zajednička ispravka) - osobina telefona i držanja,
     * pa se čuvaju i posle Reset-a i zatvaranja aplikacije. Zapis: "11.6;13.7;7.4".
     */
    fun headingErrors(context: Context): List<Float> =
        prefs(context).getString(KEY_HEADING_ERRORS, null).orEmpty().split(';').mapNotNull { it.toFloatOrNull() }

    fun setHeadingErrors(context: Context, errors: List<Float>) =
        prefs(context).edit { putString(KEY_HEADING_ERRORS, errors.joinToString(";")) }

    /** Omiljena odredišta (nazivi sala, zgrada i službi kao u izboru odredišta), redom dodavanja. */
    fun favorites(context: Context): List<String> = names(context, KEY_FAVORITES)

    fun setFavorites(context: Context, names: List<String>) = setNames(context, KEY_FAVORITES, names)

    /** Nedavna odredišta, najnovije prvo ([com.example.ftnnavigation.campus.withRecent]). */
    fun recents(context: Context): List<String> = names(context, KEY_RECENTS)

    fun setRecents(context: Context, names: List<String>) = setNames(context, KEY_RECENTS, names)

    // Nazivi odredišta nemaju prelom reda, pa je on razdvajač.
    private fun names(context: Context, key: String): List<String> =
        prefs(context).getString(key, null).orEmpty().split('\n').filter { it.isNotEmpty() }

    private fun setNames(context: Context, key: String, names: List<String>) =
        prefs(context).edit { putString(key, names.joinToString("\n")) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
