package com.example.ftnnavigation.schedule

import kotlinx.serialization.Serializable
import java.time.LocalDate
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
    val level: String, // OAS / OSS (osnovne akademske / strukovne), MAS / MSS (master akademske / strukovne)
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

    val semesterKind: SemesterKind get() = SemesterKind.of(semester)

    /** "OAS" (osnovne) ili "MAS" (master), bez razlike akademske / strukovne - kao u izboru. */
    val degree: String get() = if (level.startsWith("M")) "MAS" else "OAS"

    val isVocational: Boolean get() = level == "OSS" || level == "MSS"

    /** Program na tom nivou: strukovne i akademske studije istog naziva su različiti programi. */
    val programKey: String get() = "$level|$programId"

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
