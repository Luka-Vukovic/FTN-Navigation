package com.example.ftnnavigation.graph

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Dao
abstract class GraphDao {
    @Query("SELECT * FROM nodes")
    abstract suspend fun nodes(): List<Node>

    @Query("SELECT * FROM edges")
    abstract suspend fun edges(): List<Edge>

    @Query("SELECT COUNT(*) FROM nodes")
    abstract suspend fun nodeCount(): Int

    @Insert
    abstract suspend fun insertNodes(nodes: List<Node>)

    @Insert
    abstract suspend fun insertEdges(edges: List<Edge>)

    /** Puni praznu bazu; transakcija sprečava dupli upis ako se pozove dvaput istovremeno. */
    @Transaction
    open suspend fun seedIfEmpty(nodes: List<Node>, edges: List<Edge>) {
        if (nodeCount() > 0) return
        insertNodes(nodes)
        insertEdges(edges)
    }
}

/**
 * Graf kampusa i zgrada. Puni se iz assets/nb.json, amf.json, kula.json, f.json, ntp.json i campus.json; svaka
 * izmena grafa, šeme ili tih JSON-ova ide uz povećanje [version] - stara baza se briše i puni iznova
 * (nema korisničkih podataka).
 */
@Database(entities = [Node::class, Edge::class], version = 24, exportSchema = false)
abstract class GraphDatabase : RoomDatabase() {
    abstract fun graphDao(): GraphDao

    companion object {
        @Volatile private var instance: GraphDatabase? = null

        fun get(context: Context): GraphDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GraphDatabase::class.java, "graph.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}

class GraphRepository(private val dao: GraphDao) {
    /** Ceo graf (mali je - stotine čvorova); [placements] smeštaju planove zgrada u kampus. */
    suspend fun load(seedNodes: List<Node>, seedEdges: List<Edge>, placements: Map<String, PlanPlacement>): BuildingGraph {
        dao.seedIfEmpty(seedNodes, seedEdges)
        return BuildingGraph(dao.nodes(), dao.edges(), placements)
    }
}
