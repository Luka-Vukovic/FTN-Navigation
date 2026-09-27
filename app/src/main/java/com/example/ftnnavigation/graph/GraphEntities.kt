package com.example.ftnnavigation.graph

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class NodeType { PROSTORIJA, VRATA, HODNIK, STEPENISTE, LIFT, ULAZ }

/** Vrsta prelaza određuje kako se računa vreme (vidi [RoutingProfile]). */
enum class EdgeType {
    /** Hod po istom spratu: dužina iz koordinata / brzina hoda. */
    HOD,

    /** Između stepeništa susednih spratova; uspon je sporiji od silaska. */
    STEPENICE,

    /** Između liftova bilo koja dva sprata: čekanje + vožnja po spratu. */
    LIFT,
}

/**
 * Čvor topološkog grafa zgrade. [x]/[y] su relativni (0..1) u odnosu na sliku plana
 * sprata - kao i pozicija na Mapi - pa metri zavise samo od kalibracije plana ([FloorScale]).
 */
@Entity(
    tableName = "nodes",
    indices = [Index("buildingId", "floor"), Index("name")],
)
data class Node(
    @PrimaryKey val id: String,
    val buildingId: String,
    val floor: Int, // 0 = prizemlje
    val x: Float,
    val y: Float,
    val type: NodeType,
    /** Naziv sale kako piše u rasporedu (npr. "NTP-307"); samo za [NodeType.PROSTORIJA]. */
    val name: String? = null,
)

/** Neusmerena ivica; vreme prolaska se ne čuva, već računa po profilu korisnika. */
@Entity(
    tableName = "edges",
    primaryKeys = ["fromId", "toId"],
    foreignKeys = [
        ForeignKey(entity = Node::class, parentColumns = ["id"], childColumns = ["fromId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Node::class, parentColumns = ["id"], childColumns = ["toId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("toId")],
)
data class Edge(
    val fromId: String,
    val toId: String,
    val type: EdgeType,
)
