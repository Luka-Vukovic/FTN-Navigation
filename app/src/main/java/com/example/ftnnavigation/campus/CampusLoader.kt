package com.example.ftnnavigation.campus

import android.content.Context
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.GraphDatabase
import com.example.ftnnavigation.graph.GraphRepository
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.OpenArea
import com.example.ftnnavigation.graph.SpiralPath
import com.example.ftnnavigation.graph.StairPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Učitavanje mape i grafa, zajedničko za Mapu i obaveštenja o polasku (koja rade i kad
 * aplikacija nije pokrenuta).
 */

/** Mapa kampusa iz assets/campus.json. */
suspend fun loadCampus(context: Context): CampusData = withContext(Dispatchers.IO) {
    CampusData.parse(context.assets.open(CampusData.ASSET).bufferedReader().use { it.readText() })
}

/** Unutrašnji graf zgrade iz assets-a (nb.json, amf.json, kula.json, ntp.json). */
suspend fun loadIndoorPlan(context: Context, asset: String): IndoorPlan = withContext(Dispatchers.IO) {
    IndoorPlan.parse(context.assets.open(asset).bufferedReader().use { it.readText() })
}

/** Putanje stepeništa sa krakovima iz planova zgrada (nisu u bazi - za kretanje tačke po stepeništu). */
suspend fun loadStairPaths(context: Context): List<StairPath> =
    INDOOR_BUILDINGS.flatMap { loadIndoorPlan(context, it.asset).stairPaths() }

/** Otvoreni prostori (hol) iz planova zgrada - za map-matching ([com.example.ftnnavigation.graph.MapMatcher]). */
suspend fun loadOpenAreas(context: Context): List<OpenArea> =
    INDOOR_BUILDINGS.flatMap { loadIndoorPlan(context, it.asset).openAreas() }

/** Spiralna stepeništa (Kula) iz planova zgrada - za kretanje tačke po stepeništu. */
suspend fun loadSpiralPaths(context: Context): List<SpiralPath> =
    INDOOR_BUILDINGS.flatMap { loadIndoorPlan(context, it.asset).spiralPaths() }

/** Graf kampusa i zgrada iz baze; prazna baza se prvo puni ([seedGraph]). */
suspend fun loadGraph(context: Context, campus: CampusData): BuildingGraph {
    val (nodes, edges) = seedGraph(campus, INDOOR_BUILDINGS.map { loadIndoorPlan(context, it.asset) })
    val repository = GraphRepository(GraphDatabase.get(context).graphDao())
    return repository.load(nodes, edges, campus.placements())
}
