package com.example.ftnnavigation.campus

import android.content.Context
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.GraphDatabase
import com.example.ftnnavigation.graph.GraphRepository
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

/** Graf kampusa i zgrada iz baze; prazna baza se prvo puni ([seedGraph]). */
suspend fun loadGraph(context: Context, campus: CampusData): BuildingGraph {
    val (nodes, edges) = seedGraph(campus)
    val repository = GraphRepository(GraphDatabase.get(context).graphDao())
    return repository.load(nodes, edges, campus.placements())
}
