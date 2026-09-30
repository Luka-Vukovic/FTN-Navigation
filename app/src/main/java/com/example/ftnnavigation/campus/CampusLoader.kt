package com.example.ftnnavigation.campus

import android.content.Context
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.GraphDatabase
import com.example.ftnnavigation.graph.GraphRepository
import com.example.ftnnavigation.graph.IndoorPlan
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.NtpPlan
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

/** Unutrašnji graf zgrade iz assets-a (nb.json, ntp.json). */
suspend fun loadIndoorPlan(context: Context, asset: String): IndoorPlan = withContext(Dispatchers.IO) {
    IndoorPlan.parse(context.assets.open(asset).bufferedReader().use { it.readText() })
}

/** Graf kampusa i zgrada iz baze; prazna baza se prvo puni ([seedGraph]). */
suspend fun loadGraph(context: Context, campus: CampusData): BuildingGraph {
    val (nodes, edges) = seedGraph(campus, loadIndoorPlan(context, NbPlan.ASSET), loadIndoorPlan(context, NtpPlan.ASSET))
    val repository = GraphRepository(GraphDatabase.get(context).graphDao())
    return repository.load(nodes, edges, campus.placements())
}
