package com.example.ftnnavigation.settings

import android.content.Context
import androidx.core.content.edit
import com.example.ftnnavigation.graph.RoutingProfile

/** Kako ruta menja sprat (Podešavanja): najbrže, ili izbegava stepenice / lift. */
enum class FloorChange(val profile: RoutingProfile) {
    /** Stepenice ili lift - šta je brže (lift: čekanje 45 s). */
    NAJBRZE(RoutingProfile()),

    /** Kolica, povreda, prtljag - liftom; gde lifta nema, stepenicama uz napomenu ([com.example.ftnnavigation.graph.Route.fallback]). */
    BEZ_STEPENICA(RoutingProfile(avoidStairs = true)),

    /** Samo stepenice. */
    BEZ_LIFTA(RoutingProfile(avoidLift = true)),
}

/** Podešavanja aplikacije sa ekrana Podešavanja (osim obaveštenja - ona su u `DepartureScheduler`). */
object AppSettings {
    private const val PREFS = "settings"
    private const val KEY_AUTO_ROTATE_MAP = "auto_rotate_map"
    private const val KEY_STAIR_TURNS = "stair_turns"
    private const val KEY_FLOOR_CHANGE = "floor_change"
    private const val KEY_CROWD_ROUTING = "crowd_routing"

    /** Mapa se okreće za po 90° po smeru korisnika; podrazumevano isključeno. */
    fun autoRotateMap(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_ROTATE_MAP, false)

    fun setAutoRotateMap(context: Context, enabled: Boolean) = prefs(context).edit { putBoolean(KEY_AUTO_ROTATE_MAP, enabled) }

    /** Promena sprata na ruti (i za vreme polaska u obaveštenju); podrazumevano najbrže. */
    fun floorChange(context: Context): FloorChange =
        prefs(context).getString(KEY_FLOOR_CHANGE, null)?.let { name -> FloorChange.entries.find { it.name == name } }
            ?: FloorChange.NAJBRZE

    fun setFloorChange(context: Context, value: FloorChange) = prefs(context).edit { putString(KEY_FLOOR_CHANGE, value.name) }

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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
