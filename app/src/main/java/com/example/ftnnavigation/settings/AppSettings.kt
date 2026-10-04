package com.example.ftnnavigation.settings

import android.content.Context
import androidx.core.content.edit

/** Podešavanja aplikacije sa ekrana Podešavanja (osim obaveštenja - ona su u `DepartureScheduler`). */
object AppSettings {
    private const val PREFS = "settings"
    private const val KEY_AUTO_ROTATE_MAP = "auto_rotate_map"

    /** Mapa se okreće za po 90° po smeru korisnika; podrazumevano isključeno. */
    fun autoRotateMap(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_ROTATE_MAP, false)

    fun setAutoRotateMap(context: Context, enabled: Boolean) = prefs(context).edit { putBoolean(KEY_AUTO_ROTATE_MAP, enabled) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
