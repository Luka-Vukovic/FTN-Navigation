package com.example.ftnnavigation.poc

import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.EdgeType
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.PointM
import com.example.ftnnavigation.graph.indoorBuilding
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

// Promena sprata na stepeništu (04.10.2026). Telefon nema barometar. Korisnik: "u većini slučajeva su stepenice za
// gore i dole dovoljno odvojene da se može prepoznati na osnovu toga" (NB: sa podesta sprata levo gore, desno dole).
// Isto rečeno okretom: stepenište sa krakovima i podestima je spirala - pri penjanju se na svakom podestu okreće na
// istu stranu, pri silasku na suprotnu. Okret od ~180° na podestu meri se pouzdano (žiroskop u rotation vector-u),
// i kad apsolutni smer (kompas) promašuje. Snimci sa terena (03.10. i 04.10.2026): NB P -> I zbir okreta +244° (udesno),
// NB I -> P −196° (ulevo); AMF 0 -> −1 +180°; F III -> dole pet okreta po ~+180° (svi udesno), letovi po 12-14 koraka.

/** Okret za ovoliko (u jednom smeru) između dva leta znači da je korisnik prošao podest između spratova - jedan sprat. */
const val STAIR_TURN_DEG = 135f

/** Svaki sledeći sprat u istom hodu: još jedan pun krug (podest sprata + podest između spratova). */
const val STAIR_TURN_PER_FLOOR_DEG = 360f

/**
 * Let stepeništa: bar ovoliko uzastopnih koraka istog smera. Na snimcima letovi imaju 7-17 koraka, a hodanje po podestu
 * pre prvog leta (F III, 03.10.2026) daje nizove od 4.
 */
const val FLIGHT_STEPS = 6

/** Koraci istog smera: svaki do ovoliko od prvog koraka niza. */
private const val RUN_DEG = 20f

/** Okret mora biti skoro završen (do ovoliko) pre leta posle njega. */
private const val TURN_SLACK_DEG = 30f

/** Pravac paralelan letu (u bilo kom smeru): do ovoliko. */
private const val PARALLEL_DEG = 30f

/** Početak leta za proveru blizine stepeništa: koraci do ovoliko od smera na kraju leta. */
private const val FLIGHT_START_DEG = 35f

/**
 * Let pre prvog okreta najviše ovoliko koraka (sa krajem prethodnog okreta). Letovi na snimcima: 7-17 koraka (F do 21 sa
 * koracima okreta); hodnik gore-dole (NTP II sprat 03.10., NB prizemlje 04.10.) 25-77 koraka.
 */
private const val MAX_FLIGHT_STEPS = 24

/** Okret između letova kroz bar ovoliko koraka (na podestu se okreće u hodu; u hodniku u mestu, između dva koraka). */
private const val MIN_TURN_STEPS = 2

/** Pauza duža od ovoga prekida let (NB 04.10.: "koraci" dok korisnik stoji, na 4-42 s). */
private const val PAUSE_NS = 2_000_000_000L

/** Izlazak sa stepeništa: bar ovoliko koraka istog smera (za vreme okreta na podestu nizovi su 1-3 koraka). */
private const val EXIT_RUN_STEPS = 4

/** Niz koraka paralelan ranijem letu je izlazak tek kad je duži od najdužeg leta za ovoliko koraka. */
private const val EXTRA_FLIGHT_STEPS = 3

/**
 * Smer okreta pri penjanju (+1 u smeru kazaljke, −1 suprotno) za stepeništa sa više od dva nivoa, gde sprat sa koga se
 * kreće ne odlučuje. Ključ: "zgrada/stepenište". NB S: korisnik ("levo gore, desno dole") i snimci; F S: snimak
 * silaska sa III sprata. Ostala (Kula, NTP S1-S3) se uče ([PdrLocator]): sa najnižeg/najvišeg nivoa smer je poznat,
 * a ispravka sprata posle promene ("Ovde sam") pokazuje pravi smer.
 */
val STAIR_UP_TURN = mapOf("NB/S" to 1, "F/S" to -1)

/** Sufiks čvora na dnu kraka naniže (stepenište sa krakovima, teren 05.10.2026): NB-1-S-D. */
const val DOWN_FOOT_SUFFIX = "-D"

/**
 * Stepenište zgrade: čvor po spratu ([nodes] - kod stepeništa sa krakovima dno kraka naviše, a na najvišem spratu dno
 * kraka naniže; [downNodes] - dno kraka naniže, na svakom spratu osim najnižeg) i čvor hodnika uz svaki (stepenište je
 * list grafa na svakom spratu).
 */
class Stair(
    val key: String,
    val buildingId: String,
    val nodes: Map<Int, Node>,
    val downNodes: Map<Int, Node>,
    private val corridorOf: Map<String, Node>,
) {
    /** Čvor hodnika uz čvor stepeništa [node]. */
    fun corridor(node: Node): Node = corridorOf.getValue(node.id)

    val corridors: Map<Int, Node> get() = nodes.mapValues { corridor(it.value) }

    /** Čvorovi stepeništa na spratu [floor] (dno kraka naviše i naniže, koliko ih ima). */
    fun nodesOn(floor: Int): List<Node> = listOfNotNull(nodes[floor], downNodes[floor]).distinct()
}

/** Stepeništa sa bar dva nivoa u zgradama sa planom, po ključu "zgrada/stepenište". */
fun stairsOf(graph: BuildingGraph): Map<String, Stair> =
    graph.nodes.filter { it.type == NodeType.STEPENISTE && indoorBuilding(it.buildingId) != null }
        .groupBy { stairKey(it) }
        .mapNotNull { (key, all) ->
            val (down, up) = all.partition { it.id.endsWith(DOWN_FOOT_SUFFIX) }
            // Na najvišem spratu stepeništa sa krakovima nema dna kraka naviše (AMF S1, S2) - tu je čvor sprata dno kraka naniže.
            val byFloor = up.associateBy { it.floor } + down.filter { d -> up.none { it.floor == d.floor } }.associateBy { it.floor }
            // Čvor hodnika je najbliži sused na istom spratu; dalji je put preko međupodesta (NB: prolaz ka Amfiteatrima).
            val corridorOf = all.associate { s ->
                s.id to graph.neighbors(s.id)
                    .filter { (n, type) -> type == EdgeType.HOD && n.floor == s.floor }
                    .minBy { (n, _) -> hypot(graph.position(n).x - graph.position(s).x, graph.position(n).y - graph.position(s).y) }
                    .first
            }
            Stair(key, all.first().buildingId, byFloor, down.associateBy { it.floor }, corridorOf)
                .takeIf { byFloor.size >= 2 }
        }
        .associateBy { it.key }

/** "NB/S" za NB-m1-S, NB-0-S, NB-1-S-D ... (id je zgrada-sprat-ključ). */
fun stairKey(node: Node): String =
    node.buildingId + "/" + node.id.removePrefix(node.buildingId + "-").substringAfter('-').removeSuffix(DOWN_FOOT_SUFFIX)

/**
 * Hod po stepeništu od ulaska: smer svakog koraka. Letovi su nizovi od bar [FLIGHT_STEPS] koraka istog smera; sprat se
 * menja samo okretom između dva leta. Okret na podestu pre prvog leta (korisnik se okreće ka letu) se ne broji
 * (03.10.2026, F III: okret od 200° ulevo kod stepeništa pre silaska bio bi "sprat", i to sa pogrešnom stranom).
 */
class StairWalk {
    private val headings = ArrayList<Float>()

    /** Vreme koraka (ns, bilo koji sat; null ako nije zadato). */
    private val times = ArrayList<Long?>()

    /** Gde je bila tačka posle svakog koraka (metri plana), ako je zadato. */
    private val positions = ArrayList<PointM?>()

    /**
     * Korak u smeru [headingDeg] u trenutku [timeNs], tačka posle njega [at]; [redoSteps] prethodnih koraka je išlo
     * pogrešnim smerom (ispravljen smer hoda).
     */
    fun step(headingDeg: Float, redoSteps: Int = 0, timeNs: Long? = null, at: PointM? = null) {
        val redo = min(redoSteps, headings.size)
        val redoneTimes = times.subList(times.size - redo, times.size).toList()
        repeat(redo) {
            headings.removeAt(headings.size - 1)
            times.removeAt(times.size - 1)
            positions.removeAt(positions.size - 1)
        }
        for (t in redoneTimes + timeNs) {
            headings += headingDeg
            times += t
            positions += at
        }
    }

    /** Pamti najviše [max] poslednjih koraka (dok se hoda van stepeništa); ne posle promene sprata. */
    fun trim(max: Int) {
        if (anchor != null) return
        val drop = headings.size - max
        if (drop <= 0) return
        headings.subList(0, drop).clear()
        times.subList(0, drop).clear()
        positions.subList(0, drop).clear()
    }

    /** Pauza pre koraka [i] (duža od [PAUSE_NS]) - let se tu prekida. */
    private fun pauseBefore(i: Int): Boolean {
        val a = times[i - 1] ?: return false
        val b = times[i] ?: return false
        return b - a > PAUSE_NS
    }

    /**
     * Prvi korak leta koji se završava korakom [end]: unazad dok su koraci do [FLIGHT_START_DEG] od smera na početku
     * niza koji se završava sa [end], bez pauze (kompas ume da odluta ~20° u toku leta - AMF 0 -> −1, let od 19 koraka
     * je dva niza; poslednji korak niza je već deo okreta).
     */
    private fun flightFirst(end: Int): Int {
        val reference = headings[runs().first { end in it }.first]
        var start = end
        while (start > 0 && !pauseBefore(start) && abs(angleDiffDeg(headings[start - 1], reference)) <= FLIGHT_START_DEG) start--
        return start
    }

    /** Gde je počeo let pre okreta koji je poslednji [floorTurn] prijavio. */
    fun flightStart(): PointM? = pendingAnchor?.let { positions[flightFirst(it)] }

    /** Koraka od početka leta pre okreta koji je poslednji [floorTurn] prijavio (sa poslednjim); 0 ako ga nema. */
    fun stepsSinceFlightStart(): Int = pendingAnchor?.let { headings.size - flightFirst(it) } ?: 0

    /** Nizovi uzastopnih koraka istog smera (svaki do [RUN_DEG] od prvog u nizu), bez pauze. */
    private fun runs(): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var start = 0
        for (i in 1 until headings.size) {
            if (pauseBefore(i) || abs(angleDiffDeg(headings[i], headings[start])) > RUN_DEG) {
                result += start until i
                start = i
            }
        }
        if (headings.isNotEmpty()) result += start until headings.size
        return result
    }

    /** Kraj leta pre prve promene sprata: od njega se meri okret (i za sledeće spratove u istom hodu). */
    private var anchor: Int? = null

    private var pendingAnchor: Int? = null

    /**
     * Strana okreta (+1 u smeru kazaljke, −1 suprotno) ako je upravo završen okret za sledeći sprat - posle [changes]
     * promena u ovom hodu, sa strane [sense] (0 dok promene nije bilo). Okret se meri od kraja leta pre prve promene do
     * početka trenutnog leta (bar [FLIGHT_STEPS] koraka) - i okret na podestu u istom smeru pre prvog leta (hodnik ->
     * stepenište, NB I -> P) tako ne smeta. Inače 0. Promenu treba potvrditi ([confirmChange]).
     */
    fun floorTurn(changes: Int, sense: Int): Int {
        val runs = runs()
        val current = runs.lastOrNull() ?: return 0
        if (current.count() < FLIGHT_STEPS) return 0
        val from = anchor ?: runs.dropLast(1).lastOrNull { it.count() >= FLIGHT_STEPS }?.last ?: return 0
        // Prva promena: let pre okreta nije duži hod (hodnik), a okret se hodao (na podestu se okreće u hodu kroz više
        // koraka; u hodniku se korisnik okrene u mestu) - šetnja hodnikom gore-dole pored stepeništa nije stepenište.
        if (anchor == null && (from - flightFirst(from) + 1 > MAX_FLIGHT_STEPS || current.first - from < MIN_TURN_STEPS)) return 0
        // Okret u mestu, za vreme pauze - samo pre prve promene. Posle nje je korisnik na stepeništu, a na podestu sme da
        // zastane (teren 05.10.2026: NB I -> III i NTP I -> II, zastao 2,2-3,3 s - pa ovo pravilo, dok je važilo za ceo
        // hod, nijedan sledeći sprat više nije prihvatalo).
        if (anchor == null && (from + 1..current.first).any(::pauseBefore)) return 0
        val sums = FloatArray(headings.size)
        for (i in 1 until headings.size) sums[i] = sums[i - 1] + angleDiffDeg(headings[i], headings[i - 1])
        val need = STAIR_TURN_DEG + STAIR_TURN_PER_FLOOR_DEG * changes
        // Ukupan okret: od srednjeg smera leta pre okreta do srednjeg smera trenutnog leta. Poslednji korak leta je već
        // deo okreta (teren 05.10.2026, NTP II -> I: od njega 133°, od srednjeg smera leta 149°; pravi okret 180°).
        val fromRun = runs.first { from in it }
        fun mean(run: IntRange) = run.map { sums[it] }.average().toFloat()
        for (s in if (sense != 0) listOf(sense) else listOf(1, -1)) {
            if (s * (sums[current.first] - sums[from]) < need - TURN_SLACK_DEG) continue
            if (s * (mean(current) - mean(fromRun)) < need) continue
            pendingAnchor = from
            flightHeadings = meanHeading(fromRun) to meanHeading(current)
            return s
        }
        return 0
    }

    /** Srednji smer leta pre okreta i trenutnog leta (magnetski azimut) po poslednjem [floorTurn] koji je prijavio okret. */
    var flightHeadings: Pair<Float, Float>? = null
        private set

    private fun meanHeading(run: IntRange): Float {
        val x = run.sumOf { cos(Math.toRadians(headings[it].toDouble())) }
        val y = run.sumOf { sin(Math.toRadians(headings[it].toDouble())) }
        return Math.toDegrees(atan2(y, x)).toFloat()
    }

    /**
     * Isti koraci, bez promene sprata - za hod van stepeništa posle silaska sa njega bez promene (teren 05.10.2026, NTP
     * I -> P: tačka je sišla i odmah opet ušla, a let pre okreta je ostao u starom hodu).
     */
    fun detached(): StairWalk = StairWalk().also {
        it.headings += headings
        it.times += times
        it.positions += positions
    }

    /** Koraka u trenutnom nizu istog smera (let posle okreta). */
    fun currentRunSteps(): Int = runs().lastOrNull()?.count() ?: 0

    /** Sprat je promenjen po poslednjem [floorTurn]. */
    fun confirmChange() {
        anchor = anchor ?: pendingAnchor
    }

    /**
     * Korisnik silazi sa stepeništa: trenutni niz koraka (bar [EXIT_RUN_STEPS] - okret na podestu se ne broji, NB P -> I)
     * nije let (nije paralelan nijednom ranijem letu) i odveo ga je ka hodniku bar [exitM] ([towardCorridorM]), ili je
     * paralelan letu, ali duži od najdužeg leta - hodnik se nastavlja pravcem leta.
     */
    fun leaves(towardCorridorM: Double, exitM: Double): Boolean {
        val runs = runs()
        val current = runs.lastOrNull() ?: return false
        if (current.count() < EXIT_RUN_STEPS) return false
        val heading = headings[current.last]
        val parallel = runs.dropLast(1).filter { f ->
            f.count() >= FLIGHT_STEPS && abs(angleDiffDeg(heading, headings[f.first])).let { it <= PARALLEL_DEG || it >= 180 - PARALLEL_DEG }
        }
        return if (parallel.isEmpty()) towardCorridorM >= exitM else current.count() > parallel.maxOf { it.count() } + EXTRA_FLIGHT_STEPS
    }
}

/**
 * Poslednja promena sprata na stepeništu: za obaveštenje i za učenje smera iz ispravke. [turn]: strana okreta (+1 u
 * smeru kazaljke); [guessed]: smer (gore/dole) nije bio poznat - pretpostavljen ka odredištu ili naviše.
 */
data class StairChange(
    val stairKey: String,
    val buildingId: String,
    val fromFloor: Int,
    val toFloor: Int,
    val turn: Int,
    val guessed: Boolean,
)
