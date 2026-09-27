package com.example.ftnnavigation.schedule

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * Format assets/schedule.json - generiše ga tools/raspored/parse_rasporedi.py iz FTN PDF rasporeda.
 * Promene polja moraju da prate skriptu.
 */

@Serializable
data class ScheduleData(
    val generatedAt: String,
    val timetables: List<Timetable>,
)

/** Raspored jednog semestra jednog programa (i opciono modula / stručne oblasti). */
@Serializable
data class Timetable(
    val programId: String,
    val program: String,
    val level: String, // OAS (osnovne) ili MAS (master)
    val semester: Int,
    val year: Int,
    val module: String? = null,
    val lastModified: String? = null,
    val source: String,
    val notes: List<String> = emptyList(),
    /** Stručna oblast -> brojevi grupa (master), npr. "EP" -> [15]. */
    val areaGroups: Map<String, List<Int>> = emptyMap(),
    val classes: List<ClassEntry>,
) {
    val id: String get() = listOf(programId, level, semester, module.orEmpty()).joinToString("|")

    /** Grupe koje se pojavljuju u rasporedu - ponuda za izbor grupe. */
    val groupNumbers: List<Int>
        get() = (classes.flatMap { it.groups.numbers } + areaGroups.values.flatten()).distinct().sorted()

    /** Časovi koje pohađa [group]; null = bez filtera (svi časovi). */
    fun classesFor(group: Int?): List<ClassEntry> = classes.filter { it.isFor(group, areaGroups) }
}

@Serializable
data class ClassEntry(
    val day: Int, // 1 = ponedeljak ... 7 = nedelja
    val date: String? = null, // ISO datum za blok nastavu; null = svake nedelje
    val start: String,
    val end: String,
    val room: String,
    val type: ClassType,
    val subject: String,
    val lecturers: List<String>,
    val groups: Groups,
) {
    val startTime: LocalTime get() = LocalTime.parse(start)
    val endTime: LocalTime get() = LocalTime.parse(end)
    val localDate: LocalDate? get() = date?.let(LocalDate::parse)

    fun occursOn(day: LocalDate): Boolean =
        localDate?.let { it == day } ?: (day.dayOfWeek.value == this.day)

    fun isFor(group: Int?, areaGroups: Map<String, List<Int>>): Boolean =
        group == null || groups.all || groups.elective || group in groups.numbers ||
            // Oblast bez poznatog mapiranja na grupe prikazujemo svima, da čas ne nestane.
            groups.areas.any { area -> areaGroups[area]?.contains(group) ?: true }
}

@Serializable
enum class ClassType { PREDAVANJE, AUDITORNE_VEZBE, RACUNARSKE_VEZBE, LABORATORIJSKE_VEZBE, OSTALO }

@Serializable
data class Groups(
    val raw: String,
    val all: Boolean,
    val elective: Boolean,
    val numbers: List<Int>,
    val areas: List<String>,
    /** Svake druge nedelje (ili smenjivanje grupa) - ne znamo koje, pa samo označavamo. */
    val biweekly: Boolean,
)

data class UpcomingClass(val entry: ClassEntry, val date: LocalDate)

/**
 * Prvi čas koji još nije završen, počev od [now]. Čas u toku se računa kao sledeći.
 * [horizonDays] pokriva i blok nastavu zakazanu po datumima nekoliko nedelja unapred.
 */
fun nextClass(classes: List<ClassEntry>, now: LocalDateTime, horizonDays: Int = 120): UpcomingClass? {
    val today = now.toLocalDate()
    for (offset in 0..horizonDays) {
        val date = today.plusDays(offset.toLong())
        val first = classes
            .filter { it.occursOn(date) && (offset > 0 || it.endTime > now.toLocalTime()) }
            .minByOrNull { it.startTime }
        if (first != null) return UpcomingClass(first, date)
    }
    return null
}
