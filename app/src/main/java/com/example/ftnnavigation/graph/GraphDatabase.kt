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
    @Query("SELECT * FROM nodes WHERE buildingId = :buildingId")
    abstract suspend fun nodes(buildingId: String): List<Node>

    @Query("SELECT e.* FROM edges e JOIN nodes n ON n.id = e.fromId WHERE n.buildingId = :buildingId")
    abstract suspend fun edges(buildingId: String): List<Edge>

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
 * Graf zgrade. Za sada se puni iz [PlaceholderGraph]; svaka izmena grafa ili šeme ide uz
 * povećanje [version] - stara baza se briše i puni iznova (nema korisničkih podataka).
 */
@Database(entities = [Node::class, Edge::class], version = 5, exportSchema = false)
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
    suspend fun loadBuilding(buildingId: String, scale: FloorScale): BuildingGraph {
        dao.seedIfEmpty(PlaceholderGraph.nodes, PlaceholderGraph.edges)
        return BuildingGraph(dao.nodes(buildingId), dao.edges(buildingId), scale)
    }
}
