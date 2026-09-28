package com.example.ftnnavigation.events

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime

/**
 * Sopstveni događaj korisnika. Datumi su ISO stringovi, vremena "HH:mm" (kao u rasporedu).
 * Nedeljni događaj se ponavlja istog dana u nedelji od [date] do [repeatUntil] (uključivo;
 * null = bez kraja) i ne gleda kalendar nastave.
 */
@Entity(tableName = "events")
data class UserEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val date: String,
    val start: String,
    val end: String,
    /** Odredište kao na Mapi (sala iz rasporeda, zgrada ili služba); null = bez mesta i rute. */
    val place: String? = null,
    val note: String? = null,
    val repeatWeekly: Boolean = false,
    val repeatUntil: String? = null,
    /** "Kreni sada" obaveštenje za ovaj događaj. */
    val notify: Boolean = true,
) {
    val localDate: LocalDate get() = LocalDate.parse(date)
    val startTime: LocalTime get() = LocalTime.parse(start)
    val endTime: LocalTime get() = LocalTime.parse(end)
    val untilDate: LocalDate? get() = repeatUntil?.let(LocalDate::parse)

    fun occursOn(day: LocalDate): Boolean {
        val first = localDate
        if (!repeatWeekly) return day == first
        val until = untilDate
        return !day.isBefore(first) && day.dayOfWeek == first.dayOfWeek && (until == null || !day.isAfter(until))
    }
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY date, start")
    fun observeAll(): Flow<List<UserEvent>>

    @Query("SELECT * FROM events")
    suspend fun all(): List<UserEvent>

    @Upsert
    suspend fun upsert(event: UserEvent)

    @Delete
    suspend fun delete(event: UserEvent)
}

/**
 * Korisnički podaci - zato posebna baza, a ne [com.example.ftnnavigation.graph.GraphDatabase]
 * (ona se briše pri svakoj promeni grafa). Promena šeme ovde traži pravu migraciju.
 */
@Database(entities = [UserEvent::class], version = 1, exportSchema = false)
abstract class EventDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile private var instance: EventDatabase? = null

        fun get(context: Context): EventDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, EventDatabase::class.java, "events.db")
                .build()
                .also { instance = it }
        }
    }
}
