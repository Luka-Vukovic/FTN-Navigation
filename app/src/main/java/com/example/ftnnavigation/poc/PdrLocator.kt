package com.example.ftnnavigation.poc

import androidx.compose.ui.geometry.Offset
import com.example.ftnnavigation.campus.BuildingDetector
import com.example.ftnnavigation.campus.CAMPUS_ID
import com.example.ftnnavigation.campus.CampusData
import com.example.ftnnavigation.campus.GpsFix
import com.example.ftnnavigation.campus.contains
import com.example.ftnnavigation.campus.distanceToWallM
import com.example.ftnnavigation.graph.BuildingGraph
import com.example.ftnnavigation.graph.EdgePoint
import com.example.ftnnavigation.graph.EdgeType
import com.example.ftnnavigation.graph.INDOOR_BUILDINGS
import com.example.ftnnavigation.graph.IndoorBuilding
import com.example.ftnnavigation.graph.MapMatcher
import com.example.ftnnavigation.graph.MatchedPosition
import com.example.ftnnavigation.graph.NbPlan
import com.example.ftnnavigation.graph.Node
import com.example.ftnnavigation.graph.NodeType
import com.example.ftnnavigation.graph.OpenArea
import com.example.ftnnavigation.graph.PlanPoint
import com.example.ftnnavigation.graph.PointM
import com.example.ftnnavigation.graph.SpiralPath
import com.example.ftnnavigation.graph.StairPath
import com.example.ftnnavigation.graph.indoorBuilding
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Gde je PDR pozicija: sprat zgrade sa planom, ili kampus ([CAMPUS_ID], sprat 0). */
data class PdrPlace(val buildingId: String, val floor: Int = 0) {
    val isCampus: Boolean get() = buildingId == CAMPUS_ID

    /** Zgrada sa planom; null na kampusu. */
    val building: IndoorBuilding? get() = indoorBuilding(buildingId)

    companion object {
        val CAMPUS = PdrPlace(CAMPUS_ID)
    }
}

/** Zašto je pozicija preskočila (za praćenje Mape i snimak). */
enum class PlaceReason {
    /** Korisnik je označio gde je (start ili rekalibracija). */
    RUCNO,

    /** Sa kampusa kroz ulaz u zgradu sa planom. */
    ULAZ,

    /** Ulazak je bio prerani: posle njega hoda uz zid, ne u zgradu - nazad na kampus ([ENTRY_CHECK_MIN_M]). */
    NIJE_USAO,

    /** Iz zgrade kroz vrata ili spojni prolaz (u drugu zgradu ili na kampus). */
    PROLAZ,

    /** GPS je potvrdio da je korisnik napolju, a PDR je još bio u zgradi. */
    GPS_IZLAZ,

    /** GPS se uporno ne slaže sa PDR-om napolju - pozicija prebačena na GPS. */
    GPS_SKOK,

    /** Stepenicama na drugi sprat (okret na podestu između dva leta, [StairWalk]). */
    STEPENICE,

    /** Liftom: prepoznata vožnja ([LiftRide]) ili izbor sprata u pitanju ([LiftPrompt]). */
    LIFT,

    /**
     * Samo u snimku: pozicija u trenutku Start-a (teren 05.10.2026 - "Ovde sam" pre Start-a se ne snima, pa replay nije
     * znao odakle je snimak krenuo).
     */
    START,
}

// Napolju: koraci + GPS (jednodimenzionalni Kalman po poziciji). Vrednosti su procena, NISU proverene na terenu.

/** Nesigurnost koju doda jedan korak (m²): smer i dužina koraka nisu tačni. */
private const val STEP_VARIANCE_M2 = 0.5

/** GPS lošiji od ovoga se napolju ne koristi. */
private const val MAX_FUSE_ACCURACY_M = 30f

/** GPS dalji od pozicije od ovoliko sigma je sumnjiv (skok), ne povlači tačku. */
private const val GATE_SIGMAS = 3.0

/** Posle ovoliko uzastopnih sumnjivih GPS lokacija veruje se GPS-u (PDR je odlutao). */
private const val MAX_GPS_OUTLIERS = 5

/** Nesigurnost pozicije na ulazu / posle ručnog označavanja (m²). */
private const val KNOWN_VARIANCE_M2 = 4.0

/**
 * Smer zamrznut napolju ([PdrLocator.headingFrozen]): korak ide pravcem kojim se GPS pomerio u poslednjih ovoliko
 * sekundi ... Teren 19.10.2026, NB -> NTP u džepu sa ugašenim ekranom (415 s): koraci zamrznutim smerom su odvlačili tačku
 * do 20-23 m od GPS-a (tačnost 6-9 m), tri GPS skoka; tačka je posle toga u NTP ušla ~10 koraka kasno.
 */
private const val GPS_COURSE_NS = 10_000_000_000L

/** ... ako se pomerio bar ovoliko (inače korak ne pomera tačku - samo GPS). */
private const val GPS_COURSE_MIN_M = 3.0

/** Lokacije lošije od ovoga ne ulaze u pravac kretanja iz GPS-a. */
private const val GPS_COURSE_MAX_ACCURACY_M = 15f

/** Korak bez poznatog smera je nesigurniji ([STEP_VARIANCE_M2]): GPS jače povlači tačku. */
private const val FROZEN_STEP_VARIANCE_M2 = 1.5

/** Najviše ovoliko lokacija u pravcu kretanja (i kad lokacije nemaju vreme - testovi). */
private const val GPS_TRACK_MAX = 12

/**
 * Napolju, smer koraka naspram pravca GPS-a ([PdrLocator.courseError]): na pravoj deonici (GPS se pomerio bar
 * [COURSE_CHECK_MIN_M] za bar 3/4 [GPS_COURSE_NS], skoro pravo; bar [COURSE_MIN_STEPS] koraka istog pravca) razlika veća od
 * [COURSE_FIX_DEG] koja traje [COURSE_HOLD_NS] je pogrešno odstupanje telefona (džep), ne greška kompasa (ta je +-15°, uči se
 * iz "Ovde sam"); ispravka je srednja vrednost razlike na početku i kraju. Teren 21.10.2026 popodne, NB -> NTP u džepu: smer
 * koraka 40-130° pored pravca GPS-a ~2,5 min ("pogrešan smer,
 * skoro pa suprotan"), i u NTP ušao sa istim odstupanjem. Na snimcima 03.-21.10. (bez zamrznutog smera) razlika > 40° na 10 s
 * prozoru samo u džepu (17.10., 21.10.) i 4 puta pojedinačno. PROCENA.
 */
private const val COURSE_CHECK_MIN_M = 10.0
private const val COURSE_GPS_STRAIGHT = 0.9
private const val COURSE_MAX_ACCURACY_M = 10f
private const val COURSE_MIN_STEPS = 8
private const val COURSE_STEP_CONSISTENCY = 0.95
private const val COURSE_FIX_DEG = 40.0
private const val COURSE_AGREE_DEG = 20.0
private const val COURSE_HOLD_NS = 10_000_000_000L

/**
 * Ispravka smera napolju ([PdrLocator.learnOutdoorBias]): greška smera bez ispravke (smer koraka naspram pravca GPS-a na pravoj
 * deonici, kao [PdrLocator.courseError], do [OUTDOOR_MAX_ERROR_DEG]), medijana poslednjih [OUTDOOR_BIAS_SAMPLES] deonica, važi
 * od [OUTDOOR_MIN_SAMPLES]. Teren 22.10.2026: ispred NTP-a pokazivač ~20° ulevo - zajednička ispravka (−17°) je iz "Ovde sam"
 * u zgradama, a napolju kompas greši manje. Svi snimci 03.-22.10. (smer koraka bez ispravke naspram GPS-a, deonice od 10 s):
 * napolju srednje +6,5° (505 deonica), u zgradama +11,2° (93 "Ovde sam"); od 16.10. napolju ~0..+5°, u zgradama +9..+19°.
 * Zaboravlja se posle [OUTDOOR_FORGET_NS] bez nove deonice (dan, držanje telefona). PROCENA.
 */
private const val OUTDOOR_BIAS_SAMPLES = 9
private const val OUTDOOR_MIN_SAMPLES = 5
private const val OUTDOOR_MAX_ERROR_DEG = 30.0
private const val OUTDOOR_FORGET_NS = 20 * 60 * 1_000_000_000L

// Prelazi.

/** Ulazak: tačka bar ovoliko unutar obrisa zgrade sa planom ... */
private const val ENTER_DEPTH_M = 1.5

/**
 * ... i ulaz te zgrade najviše ovoliko daleko (inače je to GPS greška uz zid, ne ulazak). Bilo 12 m: na terenu
 * (03.10.2026, NTP) GPS i PDR su korisnika koji ide spolja uz zid držali ~4 m u obrisu, pa je ulazak okinuo
 * ~12 m (10 s) pre vrata; sa 6 m na istom snimku ~5 m pre vrata (GPS je tu promašivao ~6 m). Manje od 5 m na
 * tom snimku više ne okida - tada ostaje "Ovde sam".
 */
private const val ENTER_RADIUS_M = 6.0

/**
 * Kad su uslovi za ulazak ispunjeni, tačka je i dalje do [ENTER_RADIUS_M] od ulaza (teren 08.10.2026: NB 5,3 m - korisnik
 * "možda 7 koraka pre"; NTP opet prerano). Ulazak tek kad koraci pređu taj put ka ulazu (deo koraka u pravcu ulaza),
 * osim ovoliko (vrata, korak). Samo za ulazak u obrisu: ulazak po pravcu koraka ([ENTER_STEPS_NEAR_M]) čeka ceo put do vrata
 * (teren 17.10.2026, AMF: "skoro 10 koraka prerano"; bez popusta i u obrisu je NB 09.10. kasnio 5 m više).
 */
private const val ENTER_SLACK_M = 1.0

/** Ulazak koji čeka se otkazuje ako se tačka udalji od ulaza više od ovoga. */
private const val ENTER_CANCEL_M = 12.0

/**
 * Provera ulaska: posle ovoliko hoda od ulaza (pomeraj koraka, ne zbir) tačka na grafu mora da se odmakne od ulaza bar
 * [ENTRY_PROGRESS_RATIO] tog pomeraja - inače korisnik ne ide u zgradu nego uz zid, pa se vraća napolje (ako i pomeraj
 * ide više od [ENTRY_ALONG_WALL_DEG] od pravca ulaz -> hodnik). Teren 09.10.2026 uveče, NTP: korisnik je išao pravo ka
 * PASAŽ-u i ~2 koraka od zida skrenuo uz zid ka glavnom ulazu - ulazak kroz PASAŽ, tačka je stajala na ulazu dok je on
 * hodao ~20 m uz zid, pa je kasnije "izašla" na drugom kraju PASAŽ-a. Mesto po koracima je tada bilo 4,3 m u obrisu
 * (greška smera i hod 1,5 m od zida), pa dubina u obrisu nije merilo.
 */
private const val ENTRY_CHECK_MIN_M = 4.0

private const val ENTRY_ALONG_WALL_DEG = 60.0

/**
 * Napolju, a tačka ovoliko duboko u obrisu zgrade sa planom [DEEP_STEPS] koraka zaredom -> u zgradi je, iako ulaz nije
 * prepoznat: na najbliži hodnik sprata ulaza. Teren 09.10.2026 uveče, NTP: posle vraćenog ulaska tačka je ~10 m od
 * glavnog ulaza (greška smera uz zid), pa je kroz hol išla "napolju" do 22 m u obrisu. GPS je istog dana korisnika koji
 * ide spolja uz zid držao do 11,6 m u obrisu - zato tek 15 m (važi samo za veće zgrade: NTP, MI, AMF).
 */
private const val DEEP_INSIDE_M = 15.0

private const val DEEP_STEPS = 3

/**
 * Ulazak duboko u obrisu: korisnik je ušao kroz ulaz kome je trag koraka napolju prošao najbliže (do [DEEP_ENTRY_NEAR_M]),
 * a hod posle tog mesta ide ka unutra (do [DEEP_ENTRY_INWARD_DEG] od pravca ulaz -> hodnik) - tačka na taj ulaz, pa ti
 * koraci kroz zgradu. Teren 10.10.2026, NTP: GPS i PDR su korisnika držali ~9 m zapadno od glavnog ulaza, pa je tačka
 * "duboko u obrisu" stavljena 16 m od vrata (na granu sale 001) - "sa zakašnjenjem, par koraka ispred mene".
 */
private const val DEEP_TRAIL_STEPS = 40

private const val DEEP_ENTRY_NEAR_M = 12.0

private const val DEEP_ENTRY_INWARD_DEG = 45.0

/** Posle ovoliko hoda od ulaza (zbir koraka) provera prestaje - korisnik je u zgradi. */
private const val ENTRY_CHECK_MAX_M = 12.0

private const val ENTRY_PROGRESS_RATIO = 0.5

/**
 * Ulazak i bez tačke u obrisu: tačka najviše ovoliko od ulaza, a poslednjih [ENTER_STEPS] koraka ide kroz vrata ka unutra
 * (do [ENTER_STEP_ANGLE_DEG] od pravca ulaz -> hodnik). Teren 09.10.2026, NB: koraci su išli na zapad kroz glavni ulaz, a
 * GPS (5-8 m) je tačku vukao na sever uz zid - ostala je napolju ~35 s, do "Ovde sam".
 */
private const val ENTER_STEPS_NEAR_M = 4.0

private const val ENTER_STEPS = 3

private const val ENTER_STEP_ANGLE_DEG = 50.0

/**
 * Isto, za ulaz kroz koji je tačka upravo izašla: strože. Teren 17.10.2026, NTP: posle izlaska na glavni ulaz FTN-a korisnik
 * je išao uz zid ka garaži (311-318°), a pravac ulaz -> hodnik u grafu je ~272° (hodnik iza vrata je malo u stranu) - 40-46°,
 * pa je tačka posle 5 s opet "ušla". Uz zid je i inače blizu 45°, pravo kroz vrata do ~20°.
 */
private const val EXITED_ENTER_STEP_ANGLE_DEG = 30.0

/** Tačka na grafu je na kraju hodnika (na čvoru prolaza/ulaza). */
private const val AT_GATE_M = 0.3

/** Izlazak kroz kraj hodnika: slobodna PDR pozicija je bar ovoliko iza čvora, napolje. */
private const val PASS_BEYOND_M = 1.0

/** Posle prelaza bar ovoliko koraka bez novog prelaza (da tačka ne skače napred-nazad na vratima). */
private const val MIN_STEPS_BETWEEN_CHANGES = 4

/**
 * Izlazak po GPS-u tek kad je GPS bar ovoliko od zida zgrade (napolju). Teren 05.10.2026, NTP: korisnik unutra, a GPS
 * ~20 s 8-10 m ispred zgrade (tačnost 3-8 m), pa skok u zgradu (13-21 m).
 */
private const val GPS_EXIT_WALL_M = 15.0

/** Izlazak po GPS-u samo uz lokaciju tačnosti do ovoliko (vidi [PdrLocator.onGps]). */
private const val GPS_EXIT_MAX_ACCURACY_M = 10f

/** Izlazak po GPS-u: ako je PDR bio ovoliko blizu ulaza, izašao je na tom ulazu. */
private const val GPS_EXIT_GATE_M = 8.0

/**
 * Izlazak po GPS-u tek posle ovoliko hoda od ulaska u zgradu ili od "Ovde sam" u njoj. Teren 21.10.2026, AMF −1: GPS je
 * posle ulaska odlutao ~15 m ka zapadu (tačnost 7-10 m) dok je korisnik išao hodnikom - izlazak 5 s posle ulaska, dvaput,
 * i 7 s posle "Ovde sam" u hodniku (drugi put posle 16 m hoda). Pravi GPS izlasci na snimcima: 12.10. NTP, 16.10. AMF.
 */
private const val GPS_EXIT_MIN_WALK_M = 30.0

// Stepenice (vrednosti su procena; snimci sa terena: letovi od 12-14 koraka, podest stepeništa NTP ~5 x 4 m).

/** Ulazak u stepenište: tačka na grafu na ivici do čvora stepeništa, najviše ovoliko od njega (ili pola ivice). */
private const val STAIR_ENTER_M = 1.5

/**
 * Ulazak u stepenište iz hodnika samo korakom do ovoliko od pravca hodnik -> stepenište (na planu, sa ispravkom smera).
 * Bilo 90°: korak skoro popreko je puštao tačku na krak dok je korisnik išao pored stepeništa ili od njega (analiza
 * promašaja 12.10.2026: NTP II 08.10. 25 m, NTP P 10.10. 11 m - korak 70-87° od pravca; NB P 09.10. 9 m - 71°).
 */
private const val STAIR_ENTER_DEG = 50f

/**
 * Let - okret - let dok tačka nije na stepeništu računa se kao stepenište ako je prvi let počeo najviše ovoliko od
 * čvora stepeništa (tačka odluta: PDR korak je 0,8 m, a gazište ~0,3 m). Okret u hodniku tik uz stepenište sa letovima
 * od bar 6 koraka bi se ovde pogrešno video kao sprat - nije proveravano na terenu.
 */
private const val STAIR_NEAR_M = 5.0

/**
 * Pun krug na spiralnom stepeništu ([SPIRAL_STAIRS]) počinje najviše ovoliko od čvora stepeništa (stepenište Kule je 4,9 x 3,3 m).
 * Bilo 4 m: posle grafa Kule I pod pravim uglovima (27.10.2026: od prolaza iz NB-a uz zid do prstena, ne koso preko stepeništa)
 * tačka je ~5 m od čvora kad okret počne, pa silazak odmah posle prolaza (18.10.) nije prepoznat.
 */
private const val SPIRAL_NEAR_M = 5.0

/** Na spiralu ([PdrLocator.startSpiral]) se ulazi do ovoliko od čvora stepeništa ... */
private const val SPIRAL_ENTER_M = 2.0

/** ... korakom do ovoliko od pravca prvog kraka. */
private const val SPIRAL_ENTER_DEG = 50f

/** Okret suprotno od smera spirale za ovoliko (od najdaljeg) - nije na spirali. */
private const val SPIRAL_BACK_DEG = 70f

/** Posle promene sprata: okret nazad za ovoliko - silazak niz spiralu (19.10. šetanje smera u mestu: 97°; 20.10. silazak: 157°). */
private const val SPIRAL_DESCENT_BACK_DEG = 135f

/** ... a okret opet napred za ovoliko od najmanjeg - nov krug (šetanje smera u mestu). */
private const val SPIRAL_RESUME_DEG = 45f

/**
 * Posle poslednjeg gazišta kraka još ovoliko koraka pravo pre nego što se smatra da je sišao (korisnik: "svako poslednje gazište
 * je veće, kvadratno, kao neki prelaz, pa se tu može napraviti i dva koraka"). Bilo 2: teren 20.10.2026, Kula I -> II - prvi
 * krak 5 koraka (dva kratka pre gazišta, pa skretanje kroz dva koraka na podestu), tačka je posle 5. koraka sišla sa spirale.
 */
private const val SPIRAL_OVERRUN_STEPS = 4

/** ... osim na prvom kraku posle promene sprata (tamo se izlazi u hodnik istim pravcem - ostaje 2 kao ranije). */
private const val SPIRAL_OVERRUN_AFTER_FLOOR_STEPS = 2

/**
 * Posle sprata iz punog kruga ([PdrLocator] spiralNearby) okret se broji dalje; ako se hod po stepeništu završi (okret nazad,
 * pravo, predugo) posle bar ovoliko okreta u istom smeru, to je još jedan sprat - poslednji krug nema okret ka sledećem kraku.
 * Teren 20.10.2026, Kula II -> P: drugi krug je do izlaska u prizemlje imao ~255°, pa je P promašen (ostalo I).
 */
private const val SPIRAL_CARRY_DEG = 225f

/**
 * ... a na poslednjem kraku kruga, kad je sprat već promenjen, ovoliko: skretanje u sledeći krug je postepeno (teren 19.10.2026
 * uveče, Kula P -> II: posle promene na I sprat 9-10 koraka na kraku ka zapadu dok smer ne pređe pola okreta ka severu) -
 * sa 2 je tačka sišla sa spirale na I spratu i II sprat nije prepoznat.
 */
private const val SPIRAL_LANDING_OVERRUN_STEPS = 4

/** Posle okreta nazad na spratu ovoliko koraka sme nazad na spiralu u drugom smeru ([PdrLocator] returnToSpiral). */
private const val SPIRAL_RETURN_STEPS = 4

/**
 * Let pored stepeništa sa nacrtanim krakovima mora ići pravcem krakova (do ovoliko, sa ispravkom smera). Greška smera u
 * zgradama je do ~15-20° (teren 03.-10.10.2026); lažna promena 10.10. (NB I sprat) je imala let ~90° od kraka.
 */
private const val FLIGHT_AXIS_DEG = 50f

/** Isto, kad je tačka na stepeništu ([PdrLocator.changeFloorOnStairs]) - tada je stepenište verovatnije, prag širi. */
private const val ON_STAIR_FLIGHT_AXIS_DEG = 65f

/**
 * Let posle okreta pored stepeništa (tačka nije na njemu) bar ovoliko koraka (na stepeništu [FLIGHT_STEPS]). Teren
 * 10.10.2026, NB I sprat: hod po holu tik uz stepenište tamo-amo pravcem krakova - "let" od 11 koraka, okret ulevo,
 * "let" od 8 koraka; pravi krak NB/NTP ima 12-14 koraka.
 */
private const val NEAR_SECOND_FLIGHT_STEPS = 9

/**
 * Tačka na stepeništu ili do ovoliko od čvora stepeništa sa više nivoa -> [PdrLocator.nearStairs] (smer hoda iz ubrzanja
 * se ne koristi). Krak sa podestom je ~4-6 m od čvora (NTP, NB).
 */
private const val STAIR_DIRECTION_NEAR_M = 8.0

/** Koliko poslednjih koraka van stepeništa se pamti za prepoznavanje leta. */
private const val WATCH_STEPS = 60

/** Korak na stepenicama se po planu pomera samo za dubinu gazišta (korisnik: "više koraka uz manju pređenu distancu"). */
private const val STAIR_STEP_M = 0.3f

/** Na stepeništu tačka ostaje najviše ovoliko od čvora stepeništa. */
private const val STAIR_RADIUS_M = 3.0

/**
 * Izlazak sa stepeništa: hod (kratkim koracima) od čvora stepeništa ka hodniku bar ovoliko, pravcem koji nije let
 * ([StairWalk.leaves]). Bilo 2 m bez provere pravca: na F (03.10.2026) treći let ide ka hodniku i tačka je izlazila usred
 * silaska.
 */
private const val STAIR_EXIT_M = 1.2

/** Ispravka sprata ("Ovde sam") do ovoliko koraka posle promene na stepeništu uči smer okreta tog stepeništa. */
private const val STAIR_LEARN_STEPS = 80

// Stepenište sa krakovima ([StairPath], teren 05.10.2026): tačka ide krakom, preko međupodesta, pa drugim krakom - kao
// na crtežu korisnika (NB: levo od lifta gore, iza lifta, desno od lifta). Ranije je stajala do 3 m od čvora stepeništa
// ("vrtela se u krugu"), a posle promene sprata bila na čvoru kraka naviše - "vezao me je za pogrešne stepenice".

/** Koraka po kraku za prikaz (snimci 05.10.2026: krakovi NB i NTP S1 9-16 koraka). */
private const val FLIGHT_TREADS = 12

/**
 * Koraka po kraku po zgradi, kad se razlikuje od [FLIGHT_TREADS]. NTP: silazak III -> P 09.10.2026 - 14, 14, 12, 14, 15
 * koraka po kraku (korisnik: "aplikacija pretpostavlja manje stepenika nego što ih stvarno ima").
 */
private val FLIGHT_TREADS_BY_BUILDING = mapOf("NTP" to 14)

/**
 * Gazišta po stepeništu i kraku (sprat, donji krak - od sprata naviše do međupodesta), izbrojano u virtuelnoj turi FTN-a
 * (korisnik, 15.10.2026): NB - levi krakovi P, I, II i III po 11 (desni verovatno isto; na jednom prelazu ima i bočnih
 * stepenika na podestu - nije modelovano); AMF S1 - bočni krakovi 12, srednji (podest -> suteren) 13; NTP S1 - prvi krak iz
 * prizemlja 13, svi ostali 14. Snimci se slažu: NB 10-11 koraka po kraku, NTP silazak 15 (11-17). Teren 16.10.2026: NB "svuda
 * do trećeg sprata po 11" (potvrđeno), NTP S2 (bliže glavnom ulazu FTN-a) 12 po kraku, brojano 4 kraka od II do P.
 */
private val FLIGHT_TREADS_BY_STAIR: Map<String, (floor: Int, lower: Boolean) -> Int> = mapOf(
    "NB/S" to { _, _ -> 11 },
    "AMF/S1" to { _, lower -> if (lower) 13 else 12 },
    "NTP/S1" to { floor, lower -> if (floor == 0 && lower) 13 else 14 },
    "NTP/S2" to { _, _ -> 12 },
)

/**
 * Prvi krak od ulaska u stepenište najviše ovoliko koraka (sa prilazom: tačka stigne do dna kraka pre korisnika - NB I -> P
 * i NTP P -> I 05.10.2026: 18 koraka do okreta; sa 18 je tačka izlazila sa stepeništa tik pred okret). Kao
 * [StairWalk]: hod hodnikom je 25-77 koraka.
 */
private const val MAX_FIRST_FLIGHT_STEPS = 24

/** Korak dalje od ovoliko od pravca kraka je početak okreta (na podestu se okreće kroz 3-6 koraka). */
private const val TURN_START_DEG = 45f

/** Okret od ovoliko je završen (sledi let); do 180° tačka ide preko podesta srazmerno okretu. */
private const val TURN_DONE_DEG = 135f

/**
 * Korak pravo: promena smera od prethodnog koraka do ovoliko. Na krakovima se smer koraka menja do ~6-11°, a pri
 * okretu na podestu 10-20° po koraku (snimci 05.10.2026) - zato podest traži i više takvih koraka zaredom.
 */
private const val STRAIGHT_STEP_DEG = 10f

/** Okret na podestu je završen kad je smer ovoliko koraka zaredom stalan. */
private const val TURN_SETTLED_STEPS = 2

/** Posle silaska sa stepeništa ponovo ulazi tek posle ovoliko koraka (tačka je još kod dna kraka). */
private const val STAIR_REENTER_STEPS = 3

/** Pravo stepenište (bez okreta): sprat se menja posle ovolikog dela puta. */
private const val STRAIGHT_CHANGE_AT = 0.75

/**
 * Stepeništa sa izmerenim krakom od 12-14 gazišta (snimci 05.-12.10.2026): okret na prvom kraku pre [FULL_FLIGHT_TURN_AT]
 * kraka nije međupodest - korisnik se vratio niz krak (ili je hodao po holu uz stepenište pravcem kraka, pa nazad: NB I
 * sprat 10.10.2026 - posle zabrane grane lifta 12.10. tačka u tom holu stiže do kraka). AMF S1 (kraći srednji krak), F,
 * Kula i MI nisu mereni.
 */
private val FULL_FLIGHT_STAIRS = setOf("NB/S", "NTP/S1", "NTP/S2", "NTP/S3")

/** Vidi [FULL_FLIGHT_STAIRS]: deo kraka (po broju koraka) pre koga okret nije međupodest. */
private const val FULL_FLIGHT_TURN_AT = 0.6

/** Prvi korak na stepeništu dalje od ovoliko od smera kraka (na planu) - nije krenuo uz krak. */
private const val NOT_ON_FLIGHT_DEG = 100f

/**
 * Na prvom kraku (pre promene sprata) ovoliko koraka zaredom dalje od [OFF_FLIGHT_DEG] od smera kraka (na planu) -> nije
 * na kraku, nego hodnikom pored stepeništa: tačka se vraća u hodnik i ti koraci se ponove tamo. Teren 12.10.2026: NTP III
 * pored S1 hodnikom naniže (koraci 73-100° od kraka) - tačka je išla krakom i posle okreta u hodniku "sišla" na II; NTP II
 * posle silaska skrenuo ka liftovima (83-107°) - tačka je nastavila niz sledeći krak; AMF prizemlje pored S2 (koraci
 * suprotno od kraka) - tačka je sišla na -1 i ostala na stepeništu 30 m od korisnika.
 */
private const val OFF_FLIGHT_STEPS = 2

/** Vidi [OFF_FLIGHT_STEPS]. Na pravim krakovima su koraci do ~40° od kraka (kompas, ulazak sa strane). */
private const val OFF_FLIGHT_DEG = 60f

/**
 * Posle takvog povratka u hodnik ([OFF_FLIGHT_STEPS]) tačka ne ulazi na isto stepenište dok se ne udalji ovoliko od
 * njegovih čvorova na spratu (ponovljeni koraci bi je opet uveli).
 */
private const val OFF_FLIGHT_CLEAR_M = 3.0

// Znak "stepenice / ravan hod" iz ubrzanja ([StairGait]) - samo za silazak sa telefonom u ruci (analiza 15.10.2026: na
// kraku medijana 3 koraka ~0,8-1,8, na podestu i u hodniku do ~0,3; penjanje se slabije vidi, džep se ne vidi).

/** Znak se gleda preko ovoliko poslednjih koraka (medijana). */
private const val GAIT_STEPS = 3

/** Silazak počinje kad bar ovoliko od poslednjih [GAIT_STEPS] koraka ima znak od bar [DESCENT_START_SCORE]. */
private const val GAIT_START_STEPS = 2
private const val DESCENT_START_SCORE = 0.4f

/** Najviše ovoliko koraka na podestu pre prvog kraka - posle toga krak počinje i bez znaka (kao ranije). */
private const val DESCENT_HOLD_STEPS = 10

/** Poslednji krak silaska: medijana znaka ispod ovoga = ravan hod... */
private const val DESCENT_FLAT_SCORE = 0.1f

/** ... a tek posle ovolikog dela kraka (ranije je verovatnije usporavanje usred kraka). */
private const val DESCENT_FLAT_MIN_PROGRESS = 0.5

/** Posle očekivanog broja gazišta medijana znaka od bar ovoliko = još je na kraku... */
private const val DESCENT_STAIR_SCORE = 0.4f

/** ... najviše ovoliko koraka preko broja gazišta. */
private const val DESCENT_EXTRA_TREADS = 4

/** Posle kraja drugog kraka još ovoliko koraka pravo -> korisnik je sišao sa stepeništa (podest sprata, hodnik). */
private const val EXIT_OVERFLOW_STEPS = 3

/** Na podestu sprata ovoliko koraka pravo bez punog okreta -> nastavio je hodnikom, ne sledećim krakom. */
private const val LANDING_EXIT_STEPS = 4

/** Na vratima prolaza sa međupodesta okret nazad ([PdrLocator] turnAtGate) se prati najviše ovoliko koraka. */
private const val LANDING_GATE_STEPS = 8

/** Na vratima prolaza sa međupodesta korak do ovoliko od pravca vrata ide napred u prolaz ([PdrLocator] gateStep). */
private const val GATE_AHEAD_DEG = 70f

/**
 * Širok međupodest (NB: iza lifta): okret od dva puta po ~90° sa hodom popreko između. Kad je okret na međupodestu već bar
 * [LANDING_ACROSS_TURN_DEG], a sa međupodesta nema prolaza, do [LANDING_ACROSS_STEPS] koraka pravo je hod preko podesta, ne
 * izlazak. Teren 12.10.2026, NB II -> III: krak, okret na ~100°, 5 koraka popreko, okret na ~180°, krak - posle 4 koraka
 * pravo tačka je vraćena na dno kraka i promena sprata je izostala.
 */
private const val LANDING_ACROSS_TURN_DEG = 60f

/** Bočni krak naniže u grafu ([PdrLocator] sideDownFlight): najviše ovoliko ivica od dna kraka do ivice sa stepenicima. */
private const val MAX_DOWN_CHAIN_EDGES = 3

/** Od čvora stepeništa do vrata prolaza sa međupodesta najviše ovoliko čvorova (AMF S1 iz prizemlja: račvanje, traka, vrh kraka, podest). */
private const val LANDING_GATE_HOPS = 6

/** Bočni krak naniže: tačka na lancu pre vrha kraka ulazi na krak tek ovoliko blizu vrha (traka uz zid do kraka - AMF S1). */
private const val SIDE_FLIGHT_TOP_M = 1.0
private const val LANDING_ACROSS_STEPS = 8

/**
 * Drugi krak bez promene sprata posle ovoliko koraka: okret nije bio podest (korisnik se okrenuo i vratio istim krakom,
 * ili let nije bio dovoljno dug za [StairWalk]) - nazad na sprat sa koga je krenuo.
 */
private const val SECOND_FLIGHT_NO_CHANGE_STEPS = FLIGHT_STEPS + 2

// Lift: vožnja se prepoznaje iz akcelerometra ([LiftRideDetector], teren 08.10.2026), a broj spratova iz pomeraja. Do
// 08.10. je pitanje "na koji sprat?" stizalo posle 10 s stajanja na 3 m od lifta - na terenu prerano (dok čeka lift, čim
// uđe), lažno (čekanje bez vožnje, posle vožnje u prizemlju) ili nikako (stavljanje telefona u džep pravi lažne korake -
// tačka se odmakla ~10 m od lifta).

/**
 * Vožnja se pripisuje najbližem liftu na spratu tačke, ako je najviše ovoliko daleko: tačka ume da se odmakne od lifta
 * (lažni koraci pri stavljanju u džep - 08.10.2026 ~10 m). Jezgra sa liftovima u NTP-u su desetinama metara razmaknuta.
 */
private const val LIFT_RIDE_NEAR_M = 15.0

/**
 * Visina sprata za broj spratova iz pomeraja vožnje. NTP (08.10.2026, 6 vožnji): 3,5-4,05 m po spratu, P -> V 19,2 m.
 * NB i Kula: ista vrednost je PRETPOSTAVKA (nije mereno).
 */
private const val FLOOR_HEIGHT_M = 3.75

/** Broj spratova (pomeraj / visina sprata) dalji od celog broja od ovoga nije siguran -> pitanje. Na snimcima do 0,16. */
private const val FLOOR_ROUNDING = 0.35

/**
 * Izmereno preko poslednjeg sprata lifta u smeru vožnje do ovoliko spratova -> taj sprat (dalje lift ne ide). Duge vožnje
 * mere više: NTP P -> V 18,9-21,0 m, V -> P do −21,0 m (teren 13.-19.10.2026) = do 0,6 sprata preko. Ceo sprat preko i dalje
 * pita ([lift_beyondLiftFloors_asks]).
 */
private const val LIFT_END_SLACK_FLOORS = 0.75

/**
 * Pitanje "na koji sprat?" posle vožnje liftom [lift] kad broj spratova iz pomeraja nije siguran: [floors] su spratovi
 * do kojih lift ide, bez sprata [fromFloor] na kome je tačka (od najvišeg); [suggested] je sprat najbliži izmerenom.
 */
data class LiftPrompt(val lift: Node, val fromFloor: Int, val floors: List<Int>, val suggested: Int?) {
    val buildingId: String get() = lift.buildingId
}

/** Vožnja liftom u zgradi [buildingId] sa sprata [fromFloor] na [toFloor] (toast). */
data class LiftChange(val buildingId: String, val fromFloor: Int, val toFloor: Int)

/** Šta je vožnja promenila: tačka je na liftu novog sprata, ili sprat nije siguran pa se pita ([PdrLocator.liftPrompt]). */
sealed interface LiftOutcome {
    data class Moved(val change: LiftChange) : LiftOutcome
    data class Ask(val prompt: LiftPrompt) : LiftOutcome
}

// Ispravka smera iz rekalibracije.

/** Najmanji put (i PDR i stvaran) od prethodnog sidra za procenu zakrenutosti smera. */
private const val MIN_BIAS_DISTANCE_M = 10.0

/** Veća razlika dužina znači da je put bio zbrkan (ili pogrešno označen) - smer se ne dira. */
private val BIAS_LENGTH_RATIO = 0.5..2.0

/**
 * PDR put od sidra (zbir koraka kao vektor) mora biti bar ovoliki deo pređenog puta (zbir dužina koraka). Teren 20.10.2026
 * (NTP III): ~190 m hodnikom tamo-nazad, pa "Ovde sam" 19 m od sidra - zbir koraka 11 m (6 % puta), "greška +50°" je naučena
 * u zajedničku ispravku i ostatak dana kvarila smer.
 */
private const val MIN_BIAS_STRAIGHTNESS = 0.5

/** Veća ispravka je verovatno pogrešno označeno mesto. */
private const val MAX_BIAS_CORRECTION_DEG = 90f

/**
 * Zajednička ispravka = prosek ovoliko poslednjih merenja. Jedno merenje promaši i 5-10° (NB 09.10.2026: +4 na 62 m, +12 na
 * 14 m istog dana), a ovoliko ih se skupi za jedan izlazak na teren.
 */
private const val SHARED_BIAS_SAMPLES = 5

// Učenje greške smera iz pravca hodnika (12.10.2026): hod duž hodnika ide njegovim pravcem, pa je prosečna razlika smera
// koraka i pravca ivice greška kompasa koju zajednička ispravka nije pokrila. Analiza promašaja 12.10.2026: greška po danu i
// zgradi −19..+13° uz zajedničku ispravku, rasipanje ~17° po koraku; NB 04.10. +27° (7,5 m), Kula 03.10. ~+30° (tačka
// zaglavljena na prolazu, prelaz u AMF nije okinuo).

/** Uči se tek posle ovoliko uzastopnih koraka duž hodnika istog pravca (tačka napreduje). */
private const val CORRIDOR_RUN_STEPS = 5

/** Korak dalje od ovoliko od pravca hodnika ne uči (skretanje, hol, ulazak u granu). */
private const val CORRIDOR_LEARN_DEG = 35f

/**
 * Ivica kraća od ovoga nema pouzdan pravac (spojnice, vrata). Hodnik NB prizemlja je zato uči samo na dugim ivicama (14 od 20
 * ivica kraće od 3 m - čvorovi naspram vrata). Dužina celog pravog niza ivica umesto jedne ivice je probana (13.10.2026):
 * NB/NTP bolje, ali u replay-u izgubljeni prolaz Kula -> NB, izlazak iz NTP-a i jedan prolaz u Kuli (srednji 4,37 -> 4,54 m).
 */
private const val CORRIDOR_MIN_EDGE_M = 3.0

/** Sitni koraci (zaustavljanje, okretanje u mestu) ne uče. */
private const val CORRIDOR_MIN_STEP_M = 0.5f

/**
 * Deo razlike koji jedan korak prenese u ispravku (~50 koraka do pune vrednosti; rasipanje procene ~2°). Replay svih snimaka
 * sa uključenom ispravkom (12.10.2026): 0,02 - srednji promašaj 4,66 -> 3,94 m, drugo mesto 7 -> 5; 0,05 i 0,1 su lošiji
 * (4,34 i 4,52 m) - brže učenje hvata i hod po holu.
 */
private const val CORRIDOR_GAIN = 0.02f

/** Najveća ispravka iz hodnika (preko toga je verovatno pogrešan hodnik). */
private const val CORRIDOR_MAX_DEG = 30f

/**
 * Ispravka iz hodnika se zaboravlja posle ovoliko vremena bez učenja: greška kompasa se menja tokom dana (09.10.2026: ujutru
 * NTP +13°, uveče NB +16° na drugu stranu - naučenih +9° od jutra je ostalo, prolaz NB -> Kula nije okinuo).
 */
private const val CORRIDOR_FORGET_NS = 20 * 60 * 1_000_000_000L

private val ROOM_NODE_TYPES = setOf(NodeType.PROSTORIJA, NodeType.VRATA)

/** Pokazivač u pravcu hodnika ([PdrLocator.corridorHeadingOnPlan]): smer do ovoliko od pravca ivice... */
private const val CORRIDOR_POINTER_DEG = 35f

/** ... ivice bar ovolike (kratke ivice su spojnice na raskrsnicama, koso). */
private const val CORRIDOR_POINTER_MIN_M = 1.5

/**
 * Poređenje PDR puta sa stvarnim pri označavanju ("Ovde sam"), od prethodnog sidra na istom spratu. Ispravka smera
 * NIJE trajno rešenje (korisnik, 03.10.2026) - ovo je pre svega merilo koliko smer trenutno promašuje.
 */
sealed interface HeadingCheck {
    /** Put kraći od [MIN_BIAS_DISTANCE_M] - ugao bi bio šum (greška dodira od 2 m na 6 m je ~20°). */
    data class TooShort(val walkedM: Float, val actualM: Float) : HeadingCheck

    /** Ispravka je uključena/isključena usred puta - PDR put je zbir koraka sa dva različita smera. */
    data object Interrupted : HeadingCheck

    /**
     * [errorDeg]: greška smera bez ispravke = PDR smer − stvarni (+ = PDR zakrenut u smeru kazaljke, kao u beleškama
     * sa terena); [residualDeg]: greška uz ispravku koja je važila na tom putu (jednaka [errorDeg] bez ispravke);
     * [lengthRatio]: PDR put / stvarni (dužina koraka). [reliable] = false: dužine se previše razlikuju ili je greška
     * > 90° (zbrkan put ili pogrešno označeno) - ne uči se. [applied]: naučena ispravka važi za dalje korake.
     */
    data class Measured(
        val errorDeg: Float,
        val residualDeg: Float,
        val walkedM: Float,
        val actualM: Float,
        val reliable: Boolean,
        val applied: Boolean,
    ) : HeadingCheck {
        val lengthRatio: Float get() = walkedM / actualM
    }
}

/**
 * Gde je korisnik: PDR na planu sprata zgrade (map-matching na graf) ili na kampusu (koraci + GPS), sa
 * prelazima između njih:
 * - kampus -> zgrada: tačka uđe u obris zgrade sa planom blizu njenog ulaza -> čvor ulaza u zgradi;
 * - zgrada -> kampus / druga zgrada: tačka na grafu stigne do kraja hodnika koji je ulaz ili spojni
 *   prolaz i korisnik nastavi napolje -> put prolaza se prelazi koracima, pa sprat/kampus iza njega;
 * - GPS potvrdi da je korisnik napolju (sveže, posle ulaska) -> kampus, iako PDR nije video izlaz.
 *
 * - stepenice: tačka stigne do čvora stepeništa -> koraci su kratki (gazište), tačka ostaje kod stepeništa, a okret
 *   na podestu menja sprat; strana okreta je gore ili dole (stepenište je spirala - [STAIR_UP_TURN], naučeno sa
 *   najnižeg/najvišeg nivoa ili iz ispravke). Hod od stepeništa ka hodniku vraća tačku na graf sprata.
 *
 * - lift: prepoznata vožnja ([LiftRide]) kod lifta -> tačka na liftu sprata izračunatog iz pomeraja ([liftRide]); kad
 *   broj spratova nije siguran, pitanje na koji sprat ([LiftPrompt], [selectLiftFloor]). Koraci dok pitanje čeka se pamte
 *   i ponove od lifta na izabranom spratu.
 *
 * Svako
 * ručno označavanje je i sidro: posle bar [MIN_BIAS_DISTANCE_M] hoda sa istog sprata, razlika između PDR puta
 * i stvarnog puta daje grešku smera; prosek poslednjih merenja je zajednička ispravka (svuda - napolju i u svim
 * zgradama), koja važi za dalje korake ako je uključena ([applyCorrection]) i čuva se ([learnedHeadingErrors]).
 *
 * Pozicije su relativne na plan mesta ([place]); na kampusu je "plan" cela mapa (sever gore).
 */
class PdrLocator(
    private val graph: BuildingGraph,
    private val campus: CampusData,
    declinationDeg: Float,
    stairPaths: List<StairPath> = emptyList(),
    openAreas: List<OpenArea> = emptyList(),
    spiralPaths: List<SpiralPath> = emptyList(),
) {
    // Krugovi spiralnih stepeništa (Kula) po ključu stepeništa.
    private val spirals: Map<String, SpiralPath> = spiralPaths.associateBy { it.stairKey }

    // Otvoreni prostori (hol) po spratu - za map-matching.
    private val areasByPlace: Map<PdrPlace, List<List<PlanPoint>>> =
        openAreas.groupBy({ PdrPlace(it.buildingId, it.floor) }, { it.polygon })

    var place = PdrPlace(NbPlan.BUILDING_ID)
        private set

    /** Slobodna PDR pozicija (bez lepljenja na graf); null dok nije postavljena. */
    var raw: Offset? = null
        private set

    /** Pozicija na grafu sprata; null na kampusu i u prolazu. */
    var match: MatchedPosition? = null
        private set

    /**
     * Poslednje pouzdano izmerene greške smera (bez ispravke), sa svih mesta. Zajednička ispravka je njihov prosek: greška je
     * na terenu skoro uvek bila istog znaka i slična svuda (+4..+17°: napolju, NB, AMF, NTP; 01.-09.10.2026) - osobina
     * telefona i držanja, ne zgrade. Ranije se učila po zgradi, pa naučena napolju nije važila u NTP-u ("smer baca udesno").
     */
    private val headingErrors = ArrayDeque<Float>()

    /** Zajednička ispravka (prosek [headingErrors]); 0 dok nije merena. */
    private val sharedBias: Float get() = if (headingErrors.isEmpty()) 0f else headingErrors.average().toFloat()

    /** Naučene greške smera (za čuvanje između pokretanja aplikacije), od najstarije. */
    val learnedHeadingErrors: List<Float> get() = headingErrors.toList()

    fun restoreHeadingErrors(errors: List<Float>) {
        headingErrors.clear()
        errors.filter { abs(it) <= MAX_BIAS_CORRECTION_DEG }.takeLast(SHARED_BIAS_SAMPLES).forEach(headingErrors::addLast)
    }

    /**
     * Ispravka smera uključena. Isključena: zakrenutost se i dalje meri i pamti, ali se ne primenjuje - svako
     * označavanje tada pokazuje celu grešku smera. Promena usred puta prekida merenje do sledećeg sidra.
     */
    var applyCorrection: Boolean = true
        set(value) {
            if (field != value && anchor != null && (walkedX != 0.0 || walkedY != 0.0)) pathInterrupted = true
            field = value
        }

    private var pathInterrupted = false

    /**
     * Smer koraka je zamrznut - senzor orijentacije i žiroskop ćute ([PdrSensorSession]). Put od sidra tada ne meri grešku
     * smera (teren 18.10.2026: napolju posle 231 s tišine "greška −34°", naučeno u zajedničku ispravku), a hod hodnikom je ne uči.
     */
    var headingFrozen: Boolean = false
        set(value) {
            if (value && anchor != null) pathInterrupted = true
            if (value) corridorRun = 0
            field = value
        }

    /**
     * Ispravka koja se dodaje azimutu (−naučena greška + ispravka iz pravca hodnika; 0 ako je isključena ili još nije
     * naučena) - ista svuda.
     */
    val headingBiasDeg: Float get() = when {
        !applyCorrection -> 0f
        place.isCampus -> -(outdoorErrorDeg ?: sharedBias)
        else -> -sharedBias + corridorBiasDeg
    }

    /** Greška smera napolju naučena iz GPS-a ([learnOutdoorBias]); null dok nije naučena - tada važi zajednička. */
    val outdoorErrorDeg: Float?
        get() = if (outdoorErrors.size < OUTDOOR_MIN_SAMPLES) null else outdoorErrors.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

    private val outdoorErrors = ArrayDeque<Float>()

    // Vreme GPS lokacije na kraju poslednje naučene deonice napolju (sledeća počinje posle nje).
    private var outdoorLearnedNs: Long? = null

    /**
     * Ispravka naučena iz hoda duž hodnika (vidi [CORRIDOR_GAIN]), preko zajedničke - jedna za sve zgrade (posebno po zgradi je
     * u replay-u bilo lošije: 3,88 -> 4,46 m srednji promašaj - greška je više osobina telefona i dana). Napolju i dok traje
     * provera posle ulaska ([entryCheck]) se ne primenjuje: napolju GPS drži poziciju, a provera "ušao pa uz zid nazad napolje"
     * je osetljiva na smer (10.10.2026, NTP: sa +6° iz F-bloka nije okinula). Briše je Reset.
     */
    val corridorBiasDeg: Float get() = if (place.isCampus || entryCheck != null) 0f else learnedCorridorBias

    private var learnedCorridorBias = 0f

    // Vreme koraka kad je ispravka iz hodnika poslednji put učena (null: nije učena ili vreme nije poznato).
    private var corridorLearnedNs: Long? = null

    // Hod duž hodnika: pravac (na planu, 0..180) i broj uzastopnih koraka kojima tačka napreduje njime.
    private var corridorAxisDeg: Float? = null
    private var corridorRun = 0

    /** Naučena greška smera (prosek poslednjih merenja, bez ispravke); null dok nije merena. */
    val headingErrorDeg: Float? get() = if (headingErrors.isEmpty()) null else sharedBias

    private val planUp: Map<String, Float> = (INDOOR_BUILDINGS.map { it.buildingId } + CAMPUS_ID)
        .associateWith { planUpMagneticAzimuthDeg(graph.placement(it), declinationDeg) }

    private val matchers = HashMap<PdrPlace, MapMatcher>()

    private fun matcherFor(place: PdrPlace): MapMatcher? =
        if (place.isCampus) null else matchers.getOrPut(place) {
            MapMatcher(graph, place.buildingId, place.floor, areas = areasByPlace[place].orEmpty())
        }

    // Napolju.
    private var varianceM2 = KNOWN_VARIANCE_M2
    private var gpsOutliers = 0

    // Koraci napolju od poslednje GPS lokacije koja je povukla tačku (0 = korisnik stoji).
    private var stepsSinceGps = 0

    // Skorašnje GPS lokacije napolju - pravac kretanja dok je smer zamrznut ([gpsCourse]).
    private val gpsTrack = ArrayDeque<GpsFix>()

    // Napolju: koraci (vreme, geografski azimut sa ispravkom, primenjena ispravka) za poređenje sa pravcem GPS-a, i od kada
    // se razlikuju ([courseError]).
    private val courseSteps = ArrayDeque<Triple<Long, Double, Float>>()
    private var courseMismatch: Pair<Long, Double>? = null

    /** U zgradi: nov detektor posle svakog sidra, da izlazak potvrde samo lokacije posle ulaska. */
    private var exitDetector: BuildingDetector? = null

    private var stepsSinceChange = MIN_STEPS_BETWEEN_CHANGES

    /** Hod od ulaska u zgradu ili od "Ovde sam" u njoj (beskonačno dok se ne zna - start iz snimka) - [GPS_EXIT_MIN_WALK_M]. */
    private var walkedInsideM = Double.POSITIVE_INFINITY

    // Sidro (poslednje poznato mesto) i zbir PDR koraka od njega, u metrima plana mesta sidra.
    private var anchorPlace: PdrPlace? = null
    private var anchor: PointM? = null
    private var walkedX = 0.0
    private var walkedY = 0.0

    // Zbir dužina koraka od sidra ([MIN_BIAS_STRAIGHTNESS]); koraci ponovljeni iznutra se mogu brojati dvaput - samo strože.
    private var walkedPathM = 0.0

    /** Pozicija pre koraka - za ponavljanje koraka kad se ispravi smer ([WalkingDirection.WalkStep.redoSteps]). */
    private class StepStart(val raw: Offset, val match: MatchedPosition?, val walkedX: Double, val walkedY: Double)

    private val stepStarts = ArrayDeque<StepStart>()

    /**
     * Kraj hodnika ([node]) kroz koji se izlazi, i čvor iza njega ([target]: kraj hodnika druge zgrade ili kampus). [alternatives]:
     * isti spojni prolaz vodi i na druge nivoe druge zgrade (trem Kula -> AMF: podest S1, sa njega -1 ili prizemlje).
     */
    private class Gateway(val node: Node, val target: Node, val alternatives: List<Node> = emptyList())

    /**
     * Cilj prolaza: podrazumevano nivo najbliži spratu sa koga se dolazi; ako je odredište u toj zgradi, nivo odredišta.
     * Teren 16.10.2026: iz Kule kroz trem dva puta pravo niz srednji krak na -1, a tačka je otišla bočnim krakom u prizemlje.
     * Na podestu se po koracima ne vidi kuda je krenuo (12.10. gore bočnim krakom - isti smer i isti znak stepenica).
     */
    private fun targetOf(gate: Gateway): Node {
        val dest = destination?.takeIf { d -> d.buildingId == gate.target.buildingId } ?: return gate.target
        return (listOf(gate.target) + gate.alternatives).minWith(compareBy({ abs(it.floor - dest.floor) }, { it.floor != gate.target.floor }))
    }

    private val gateways: Map<PdrPlace, List<Gateway>> = buildGateways()

    /**
     * Ulaz kampusa ([outside], K-U-...) i čvor zgrade sa planom iza njega; [inward]: jedinični pravac od tog čvora ka
     * hodniku (metri kampusa), null ako ga nema.
     */
    private class Entrance(val outside: Node, val inside: Node, val inward: PointM?)

    /**
     * Ulazak čeka: tačka je bila [remainingM] od ulaza [entrance]; [walkedM] je zbir koraka od tada. [byDirection]: čeka po
     * koracima kroz vrata ([ENTER_STEPS_NEAR_M]) - broji se samo deo koraka ka unutra.
     */
    private class PendingEntry(val entrance: Entrance, val remainingM: Double, val byDirection: Boolean = false) {
        var walkedM = 0.0
    }

    private var pendingEntry: PendingEntry? = null

    /** Posle ulaska sa kampusa: koraci od ulaza (metri kampusa), za proveru da je stvarno ušao ([ENTRY_CHECK_MIN_M]). */
    private class EntryCheck(val entrance: Entrance) {
        val steps = ArrayList<PointM>()
        var walkedM = 0.0
    }

    private var entryCheck: EntryCheck? = null

    /** Ulaz kroz koji je ulazak vraćen - ne važi dok se tačka ne odmakne od njega (inače bi odmah opet ušla). */
    private var rejectedEntrance: Node? = null

    /**
     * Ulaz kroz koji je korisnik upravo izašao: kroz njega se ponovo ulazi samo po pravcu koraka, ne po tački u obrisu, dok
     * se tačka ne odmakne više od [ENTER_CANCEL_M]. Teren 10.10.2026, NTP: posle izlaska kroz glavni ulaz korisnik je stajao
     * ispred vrata (beleška, sitni koraci), GPS je tačku povukao u obris i 33 s posle izlaska ona je opet "ušla".
     */
    private var exitedEntrance: Node? = null

    /** Uzastopni koraci napolju sa tačkom [DEEP_INSIDE_M] u obrisu zgrade sa planom. */
    private var deepSteps = 0

    /** Poslednji koraci napolju (jedinični pravci, metri kampusa) - za ulazak po pravcu koraka. */
    private val campusSteps = ArrayDeque<PointM>()

    /** Korak napolju: tačka posle njega (metri kampusa), smer i dužina - za ulazak duboko u obrisu ([DEEP_TRAIL_STEPS]). */
    private class TrailStep(val at: PointM, val azimuthDeg: Float, val lengthM: Float)

    private val campusTrail = ArrayDeque<TrailStep>()

    private val entrances: Map<String, List<Entrance>> = graph.nodes
        .filter { it.buildingId == CAMPUS_ID && it.type == NodeType.ULAZ }
        .flatMap { e ->
            graph.neighbors(e.id).map { it.first }.filter { it.isIndoor }.map { inside ->
                val from = graph.position(inside)
                val inward = graph.neighbors(inside.id).map { it.first }
                    .firstOrNull { it.buildingId == inside.buildingId && it.floor == inside.floor }
                    ?.let { graph.position(it) }
                    ?.let { to -> distance(from, to).takeIf { it > 0.1 }?.let { PointM((to.x - from.x) / it, (to.y - from.y) / it) } }
                Entrance(e, inside, inward)
            }
        }
        .groupBy { it.inside.buildingId }

    /**
     * Prolaz u toku, u metrima kampusa: od kraja hodnika [start] do čvora iza njega ([target]). Pređeno se meri
     * u pravcu hodnika ka izlazu ([outward], jedinični) - dugi prolazi (NB - AMF, Kula - AMF) su produžetak
     * hodnika, a kod kratkih (vrata, NB - Kula) se krajevi dve zgrade skoro poklapaju pa pravac do cilja ne znači ništa.
     */
    private class Passage(
        val from: PdrPlace,
        val target: Node,
        val start: PointM,
        val end: PointM,
        val outward: PointM,
        var doneM: Double,
        /** Prolaz sa međupodesta (AMF S1: trem ka Kuli) - hod po stepeništu pre njega, za povratak na međupodest. */
        val resume: Climb? = null,
    ) {
        val lengthM = distance(start, end)

        /** Okret u prolazu (+ u smeru kazaljke) - strana okreta bira krak pri povratku na međupodest. */
        var turned = 0f
        var lastHeading: Float? = null
    }

    private var passage: Passage? = null

    private val stairs: Map<String, Stair> = stairsOf(graph)

    /**
     * Putanje stepeništa sa krakovima po (ključ stepeništa, sprat); može više varijanti (AMF S1: sa podesta levim ili
     * desnim bočnim krakom).
     */
    private val paths: Map<Pair<String, Int>, List<List<PlanPoint>>> =
        stairPaths.groupBy({ it.stairKey to it.floor }, { it.points })

    /**
     * Strana okreta pri penjanju po crtežu krakova (+1 u smeru kazaljke): od kraka naviše ka međupodestu i drugom kraku -
     * ako je ista za sve varijante. NB S i NTP S1-S3: +1 (snimci 05.10.2026 se slažu). Pravo stepenište nema okret.
     */
    private val pathUpTurns: Map<String, Int> = stairPaths.groupBy { it.stairKey }.mapNotNull { (key, list) ->
        list.map { upTurnOf(it.points) }.distinct().singleOrNull()?.takeIf { it != 0 }?.let { key to it }
    }.toMap()

    /** Varijante sa najnižeg sprata sa crtežom - za sprat bez crteža krakova (NB V sprat; isto mesto u zgradi). */
    private val anyPaths: Map<String, List<List<PlanPoint>>> = stairPaths.groupBy { it.stairKey }
        .mapValues { (_, list) -> list.filter { it.floor == list.minOf { p -> p.floor } }.map { it.points } }

    private fun pathsOf(stair: Stair, floor: Int): List<List<PlanPoint>>? = paths[stair.key to floor] ?: anyPaths[stair.key]

    /** Putanja hoda [c] na trenutnom spratu (varijanta [Climb.variant]). */
    private fun pathOf(c: Climb): List<PlanPoint>? = pathsOf(c.stair, place.floor)?.let { it[c.variant.coerceIn(it.indices)] }

    /** Bira varijantu putanje čija je strana okreta pri penjanju [upTurn] (ako je ima). */
    private fun selectVariant(c: Climb, upTurn: Int) {
        val variants = pathsOf(c.stair, place.floor) ?: return
        variants.indexOfFirst { upTurnOf(it) == upTurn }.takeIf { it >= 0 }?.let { c.variant = it }
    }

    /** Naučen smer okreta pri penjanju po stepeništu (+1 u smeru kazaljke, −1 suprotno); važi pre [STAIR_UP_TURN]. */
    private val learnedUpTurns = HashMap<String, Int>()

    /** Naučeni smerovi okreta (za čuvanje između pokretanja aplikacije). */
    val learnedStairTurns: Map<String, Int> get() = learnedUpTurns.toMap()

    fun restoreStairTurns(turns: Map<String, Int>) {
        learnedUpTurns.putAll(turns.filterValues { it == 1 || it == -1 })
    }

    /** Odredište (zgrada i sprat), ako je zadato - kad se smer na stepeništu ne zna, pretpostavlja se ka njemu. */
    var destination: PdrPlace? = null

    /** Poslednja promena sprata na stepeništu; null posle ručnog označavanja ili Reset-a. */
    var lastStairChange: StairChange? = null
        private set

    private var stepsSinceStairChange = 0

    /**
     * Hod po stepeništu [stair]: tačka je na čvoru stepeništa (ili na ivici ka hodniku), [offsetX]/[offsetY] je hod
     * kratkim koracima od čvora (metri plana). [sense]: strana okreta prve promene sprata (0 dok je nije bilo).
     */
    private class Climb(val stair: Stair, val walk: StairWalk, var sense: Int = 0, var changes: Int = 0) {
        var offsetX = 0.0
        var offsetY = 0.0

        // Stepenište sa krakovima ([StairPath]): deo putanje i koliko je pređeno (0..1).
        /** Penje se (putanja od dna kraka naviše) ili silazi (od dna kraka naniže). */
        var up = true
        var part = ClimbPart.FIRST
        var progress = 0.0

        /** Smer kraka kojim se ide (magnetski azimut), dok se ide pravo. */
        var flightHeading: Float? = null
        var lastHeading: Float? = null

        /** Okret od početka podesta (+ u smeru kazaljke). */
        var turned = 0f

        /** Uzastopni koraci pravo (na podestu) / posle kraja kraka. */
        var straight = 0

        /** Koraka na drugom kraku; promena sprata u ovom delu hoda. */
        var secondSteps = 0
        var changedInLeg = false

        /** Silazi sa međupodesta u prolaz: ivica (čvor pre prolaza, čvor prolaza). */
        var exitGate: Pair<Node, Node>? = null

        /** Prolaz je pravo ispred kraja prvog kraka ([gateAhead], AMF S1: trem ka Kuli), ne sa strane međupodesta (NB). */
        var exitAhead = false

        /** Posle izlaska na vrata prolaza sa međupodesta ([exitAhead]), pre ulaska u prolaz: okret i broj koraka ([turnAtGate]). */
        var gateTurned = 0f
        var gateSteps = 0

        /** Sišao sa podesta sprata hodom pravo - prošao pored sledećeg kraka, ne ulazi ponovo na ovo stepenište ([leaveStairs]). */
        var walkedPastFloorLanding = false

        /** Varijanta putanje (AMF S1: levi ili desni bočni krak). */
        var variant = 0

        /** Koraci od ulaska u stepenište - ponavljaju se u hodniku ako se pokaže da nije bio na kraku ([OFF_FLIGHT_STEPS]). */
        val steps = mutableListOf<PendingStep>()

        /** Uzastopni koraci prvog kraka dalje od [OFF_FLIGHT_DEG] od smera kraka. */
        var offFlight = 0

        /** Pozicija pri ulasku (tačka na grafu, slobodna pozicija, zbir koraka za merenje smera) - za povratak u hodnik. */
        var entryRaw: Offset? = null
        var entryMatch: MatchedPosition? = null
        var entryWalked = 0.0 to 0.0

        /** Znak stepenica poslednjih koraka ([StairGait.score]; null = ne zna se - džep, nema baze). */
        val scores = ArrayDeque<Float?>()

        /** Silazak: prvi krak je počeo po ubrzanju (dotle je hod po podestu sprata ka kraku - [DESCENT_HOLD_STEPS]). */
        var descentStarted = false
        var heldSteps = 0

        /** Posle očekivanog broja gazišta koraci i dalje liče na stepenice - koliko ih je takvih bilo ([DESCENT_EXTRA_TREADS]). */
        var extraTreads = 0
    }

    /** Delovi putanje kroz stepenište: krak, međupodest, drugi krak (već sprat iznad/ispod), podest sprata ka sledećem kraku. */
    private enum class ClimbPart { FIRST, LANDING, SECOND, FLOOR_LANDING }

    private var climb: Climb? = null

    /** Stepenište (ključ, sprat) sa koga se tačka upravo vratila u hodnik ([backToCorridor]). */
    private var offFlightStair: Pair<String, Int>? = null

    /** Hod po stepeništu koji se završio pravo u prolaz sa međupodesta ([Climb.exitAhead]) - prolaz ga pamti ([Passage.resume]). */
    private var landingClimb: Climb? = null

    private var stepsSinceStairExit = STAIR_REENTER_STEPS

    /** Dužina poslednjeg koraka kakvu je dao detektor (i na stepeništu - [lastStepM] je tamo gazište). */
    private var walkStepM = 0.8f

    /** Koraci po spratu van stepeništa: let - okret - let blizu stepeništa je hod po stepeništu i kad tačka nije na njemu. */
    private var watch = StairWalk()

    /**
     * Koraci za pun krug spiralnog stepeništa ([spiralNearby]) i mesto posle svakog. Ne briše se pri prolazu u drugu zgradu
     * (isti sprat): prvi krak spirale Kule ide ka prolazu u NB, pa je tačka na prvim stepenicima prošla u NB i nazad (teren
     * 19.10.2026, kula-up), a [watch] se pri svakom prelazu briše - krug se nije video.
     */
    private var spiralWatch = StairWalk()
    private val spiralPlaces = ArrayList<PdrPlace>()

    private fun clearSpiral() {
        spiralWatch = StairWalk()
        spiralPlaces.clear()
        spiralStash = null
    }

    /**
     * Koraci za pun krug od pre ulaska na spiralu ([beginSpiral]) - vraćaju se kad se pokaže da nije bio na spirali
     * ([replayAsCorridor]). Teren 22.10.2026, Kula I -> P: tačka je na spiralu ušla tek pri kraju prvog kraka naniže, skretanje
     * ka drugom kraku je proglašeno prilazom (penjanje), pa "nije na spirali" - a ulazak je obrisao ~200° okreta od prilaza, pa
     * ni pun krug (~400° do prizemlja) nije promenio sprat.
     */
    private var spiralStash: Pair<StairWalk, List<PdrPlace>>? = null

    private fun stashSpiral() {
        if (spiralStash == null) spiralStash = spiralWatch to spiralPlaces.toList()
        spiralWatch = StairWalk()
        spiralPlaces.clear()
    }

    private fun restoreSpiral() {
        val (walk, places) = spiralStash ?: return clearSpiral()
        spiralWatch = walk
        spiralPlaces.clear()
        spiralPlaces += places
        spiralStash = null
    }

    /** Okret posle sprata iz punog kruga ([SPIRAL_CARRY_DEG]): [turn] u smeru tog kruga, od kraja kruga (360°). */
    private class SpiralCarry(val stair: Stair, val sense: Int, var floor: Int, var turn: Float, var lastHeading: Float) {
        var maxTurn = maxOf(turn, 0f)
        var steps = 0
        var runHeading = lastHeading
        var run = 1

        // Koraci posle najvećeg okreta (izlazak sa stepeništa) - ponove se kao hod sa novog sprata.
        val afterMax = ArrayList<PendingStep>()
    }

    private var spiralCarry: SpiralCarry? = null

    private fun carrySpiral(c: SpiralCarry, azimuthDeg: Float, lengthM: Float, timeNs: Long?): PlaceReason? {
        val heading = headingOnPlan(azimuthDeg)
        c.turn += angleDiffDeg(heading, c.lastHeading) * c.sense
        c.lastHeading = heading
        c.steps++
        if (abs(angleDiffDeg(heading, c.runHeading)) > RUN_DEG) {
            c.runHeading = heading
            c.run = 1
        } else {
            c.run++
        }
        if (c.turn > c.maxTurn) {
            c.maxTurn = c.turn
            c.afterMax.clear()
        } else {
            c.afterMax += PendingStep(azimuthDeg, lengthM, 0, timeNs)
        }
        val dir = if (c.sense == SPIRAL_STAIRS.getValue(c.stair.key)) 1 else -1
        if (c.turn >= SPIRAL_TURN_DEG) {
            // Još jedan pun krug bez izlaska: sprat dalje.
            val node = c.stair.nodes[c.floor + dir] ?: run { spiralCarry = null; return null }
            trace?.invoke("stepenište ${c.stair.key}: još jedan krug -> sprat ${c.floor + dir}")
            val from = c.floor
            c.floor += dir
            c.turn -= 360f
            c.maxTurn = maxOf(c.turn, 0f)
            c.steps = 0
            c.afterMax.clear()
            moveTo(node)
            spiralCarry = c
            lastStairChange = StairChange(c.stair.key, c.stair.buildingId, from, c.floor, c.sense, guessed = false)
            stepsSinceStairChange = 0
            stepsSinceStairExit = 0
            offFlightStair = c.stair.key to c.floor
            return PlaceReason.STEPENICE
        }
        if (c.maxTurn - c.turn <= SPIRAL_BACK_DEG && c.run <= SPIRAL_MAX_RUN && c.steps <= SPIRAL_MAX_STEPS) return null
        spiralCarry = null
        if (c.maxTurn < SPIRAL_CARRY_DEG) return null
        val node = c.stair.nodes[c.floor + dir] ?: return null
        trace?.invoke("stepenište ${c.stair.key}: izlazak posle ${c.maxTurn.toInt()}° - sprat ${c.floor + dir}")
        val from = c.floor
        moveTo(node)
        lastStairChange = StairChange(c.stair.key, c.stair.buildingId, from, from + dir, c.sense, guessed = false)
        stepsSinceStairChange = 0
        stepsSinceStairExit = 0
        offFlightStair = c.stair.key to from + dir
        clearSpiral()
        for (s in c.afterMax.toList()) step(s.azimuthDeg, s.lengthM, 0, s.timeNs)
        return PlaceReason.STEPENICE
    }

    private fun trackSpiral(azimuthDeg: Float, redo: Int, timeNs: Long?, at: PointM) {
        if (spiralPlaces.lastOrNull()?.floor?.let { it != place.floor } == true) clearSpiral()
        val r = min(redo, spiralPlaces.size)
        repeat(r) { spiralPlaces.removeAt(spiralPlaces.size - 1) }
        spiralWatch.step(azimuthDeg, redo, timeNs, at)
        repeat(r + 1) { spiralPlaces += place }
        spiralWatch.trim(WATCH_STEPS)
        while (spiralPlaces.size > WATCH_STEPS) spiralPlaces.removeAt(0)
    }

    /**
     * Koliko je poslednji korak stvarno prešao (m): na stepeništu samo gazište. Kad se let prepozna tek posle okreta
     * ([stairTurnNearby]), u ovom koraku se oduzima višak već brojanih koraka leta (zbir za "Pređeno" ostaje tačan).
     */
    var lastStepM = 0f
        private set

    /** Liftovi po spratu zgrade. */
    private val lifts: Map<PdrPlace, List<Node>> = graph.nodes
        .filter { it.type == NodeType.LIFT && it.isIndoor }
        .groupBy { it.place }

    /** Pitanje za lift čeka odgovor; null = nema pitanja. */
    var liftPrompt: LiftPrompt? = null
        private set

    /** Koraci od pitanja za lift (ponavljaju se od lifta na izabranom spratu). */
    private class PendingStep(val azimuthDeg: Float, val lengthM: Float, val redoSteps: Int, val timeNs: Long?)

    private val liftSteps = mutableListOf<PendingStep>()

    /**
     * Na stepeništu ili do [STAIR_DIRECTION_NEAR_M] od njega (tačka na grafu, inače slobodna pozicija) - za
     * [WalkingDirection.nearStairs]. Okret na podestu se prepoznaje tek posle prvog leta, pa se gleda i blizina.
     */
    val nearStairs: Boolean
        get() {
            if (climb != null || spiralWalk != null) return true
            if (place.isCampus || passage != null) return false
            val p = match?.let { Offset(it.shownX, it.shownY) } ?: raw ?: return false
            val here = local(p.x, p.y)
            return stairs.values.any { stair ->
                stair.buildingId == place.buildingId &&
                    stair.nodesOn(place.floor).any { distance(local(it.x, it.y), here) <= STAIR_DIRECTION_NEAR_M }
            }
        }

    /** Tačka je na stepeništu (hod po kraku/podestu) - [WalkingDirection.onStairs]. */
    val onStairs: Boolean get() = climb != null || spiralWalk != null

    /** Smer za prikaz, u odnosu na "gore" plana mesta. */
    fun headingOnPlan(azimuthDeg: Float): Float = normalizeDeg(azimuthDeg + headingBiasDeg - planUp.getValue(place.buildingId))

    /**
     * Pravac hodnika na kome je tačka, u smeru [planHeadingDeg] (smer na planu), ako je smer do [CORRIDOR_POINTER_DEG] od
     * pravca ivice hodnik - hodnik (bar [CORRIDOR_POINTER_MIN_M]); inače null. Za pokazivač dok se hoda (teren 17.10.2026:
     * "neprecizan smer pokazivača" - posle ispravke je smer koraka u hodniku u proseku 2-7° pored, a pojedinačno ±16°;
     * tačka je zalepljena za hodnik, a pokazivač se klatio).
     */
    fun corridorHeadingOnPlan(planHeadingDeg: Float): Float? {
        if (place.isCampus || climb != null || spiralWalk != null || passage != null) return null
        val point = match?.point ?: return null
        if (point.from.type != NodeType.HODNIK || point.to.type != NodeType.HODNIK) return null
        val a = local(point.from.x, point.from.y)
        val b = local(point.to.x, point.to.y)
        if (distance(a, b) < CORRIDOR_POINTER_MIN_M) return null
        val axis = azimuthOf(b.x - a.x, b.y - a.y)
        val d = abs(angleDiffDeg(planHeadingDeg, axis))
        return when {
            d <= CORRIDOR_POINTER_DEG -> normalizeDeg(axis)
            d >= 180 - CORRIDOR_POINTER_DEG -> normalizeDeg(axis + 180)
            else -> null
        }
    }

    /**
     * Korisnik je označio gde je ([point] na planu [place]) - start ili rekalibracija. Vraća poređenje puta od
     * prethodnog sidra; null ako sidro nije na istom spratu (start, stepenice, prolaz). Pouzdano izmerena greška
     * se pamti kao zakrenutost zgrade (primenjuje se ako je [applyCorrection]). [measure] = false: samo pozicija, bez
     * merenja smera i učenja (replay: START red snimka nije označavanje).
     */
    fun setPosition(place: PdrPlace, point: Offset, measure: Boolean = true): HeadingCheck? {
        // Označio je gde je - pitanje za lift (ako čeka) više ne važi.
        clearLift()
        exitedEntrance = null
        if (!measure) {
            lastStairChange = null
            clearSpiral()
            setAnchor(place, point)
            varianceM2 = KNOWN_VARIANCE_M2
            return null
        }
        learnStairTurn(place)
        clearSpiral()
        val check = checkHeading(place, point)
        if (check is HeadingCheck.Measured && check.reliable) {
            headingErrors.addLast(angleDiffDeg(check.errorDeg, 0f))
            if (headingErrors.size > SHARED_BIAS_SAMPLES) headingErrors.removeFirst()
        }
        setAnchor(place, point)
        if (!place.isCampus) walkedInsideM = 0.0
        varianceM2 = KNOWN_VARIANCE_M2
        return check
    }

    /**
     * Ispravka kakvu je telefon imao ([headingBiasDeg]; replay: START red nosi primenjenu ispravku, od 17.10.2026 i deo iz
     * hodnika [corridorDeg]). Snimci bez njega: ceo [deg] postaje zajednička, a iz hodnika 0 - i telefon je posle
     * ubijanja procesa (17.10.2026, NTP) krenuo bez nje, a replay ju je nosio iz prethodnog snimka (pa sabirao dvaput).
     * [outside]: START je bio napolju - tamo se deo iz hodnika ne primenjuje, pa nije u [deg].
     */
    internal fun restoreBias(deg: Float, corridorDeg: Float = 0f, outside: Boolean = false) {
        headingErrors.clear()
        headingErrors.addLast(-(deg - if (outside) 0f else corridorDeg))
        learnedCorridorBias = corridorDeg
        corridorLearnedNs = lastStepNs.takeIf { corridorDeg != 0f }
    }

    /** Ispravka naučena iz hodnika, i kad se trenutno ne primenjuje (napolju) - za snimak. */
    internal val learnedCorridorBiasDeg: Float get() = learnedCorridorBias

    /** Start napolju sa GPS lokacije. */
    fun startFromGps(fix: GpsFix) {
        clearLift()
        setAnchor(PdrPlace.CAMPUS, relative(PdrPlace.CAMPUS, fix.point))
        varianceM2 = (fix.accuracyM * fix.accuracyM).toDouble()
    }

    /**
     * Novo praćenje: koraci od pre se ne ponavljaju, a ni let stepeništa se ne nastavlja (replay: lažna promena posle
     * Start-a).
     */
    fun clearRedo() {
        stepStarts.clear()
        watch = StairWalk()
        clearSpiral()
        clearLift()
    }

    /**
     * Briše poziciju; mesto ostaje (za prikaz). Naučena ispravka smera ostaje - osobina telefona i držanja, ne jednog
     * praćenja (kao strana okreta na stepeništu).
     */
    fun reset() {
        raw = null
        match = null
        passage = null
        anchor = null
        anchorPlace = null
        exitDetector = null
        rejectedEntrance = null
        exitedEntrance = null
        learnedCorridorBias = 0f
        corridorLearnedNs = null
        corridorRun = 0
        outdoorErrors.clear()
        outdoorLearnedNs = null
        stepStarts.clear()
        climb = null
        spiralWalk = null
        offFlightStair = null
        watch = StairWalk()
        lastStairChange = null
        clearLift()
    }

    /**
     * Prepoznata vožnja liftom ([LiftRideDetector]): najbliži lift na spratu tačke (u zgradi, van prolaza, najviše
     * [LIFT_RIDE_NEAR_M]) i broj spratova iz pomeraja. Siguran broj, a lift ide do tog sprata -> tačka na liftu tog sprata
     * ([LiftOutcome.Moved]); inače pitanje ([LiftOutcome.Ask], sa spratom najbližim izmerenom). null: nije kod lifta (ili
     * pitanje već čeka).
     */
    fun liftRide(ride: LiftRide): LiftOutcome? {
        if (liftPrompt != null) return null
        val raw = raw ?: return null
        if (passage != null || spiralWalk != null || place.isCampus) return null
        val here = match?.let { local(it.shownX, it.shownY) } ?: local(raw.x, raw.y)
        val lift = lifts[place].orEmpty()
            .minByOrNull { distance(local(it.x, it.y), here) }
            ?.takeIf { distance(local(it.x, it.y), here) <= LIFT_RIDE_NEAR_M }
            ?: return null
        val targets = graph.neighbors(lift.id)
            .filter { (other, type) -> type == EdgeType.LIFT && other.buildingId == lift.buildingId }
            .associate { it.first.floor to it.first }
        if (targets.isEmpty()) return null
        val from = place.floor
        val measured = ride.heightM / FLOOR_HEIGHT_M
        var floors = Math.round(measured).toInt()
        // Malo preko poslednjeg sprata lifta u smeru vožnje -> taj sprat (dalje lift ne ide). Teren 13.10.2026, NTP V -> P:
        // −20,3 m = 5,4 sprata; 19.10.2026 P -> V +21,0 m i V -> P −20,9 m = 5,6 sprata - oba puta pitanje.
        val end = (if (ride.up) targets.keys.maxOrNull() else targets.keys.minOrNull())
        if (end != null && end != from && (if (ride.up) from + measured > end else from + measured < end) &&
            abs(from + measured - end) <= LIFT_END_SLACK_FLOORS) {
            floors = end - from
        }
        val target = targets[from + floors]
        if (floors != 0 && (abs(measured - floors) <= FLOOR_ROUNDING || from + floors == end) && target != null) {
            liftSteps.clear()
            lastStairChange = null
            moveTo(target)
            return LiftOutcome.Moved(LiftChange(lift.buildingId, from, target.floor))
        }
        // Nesigurno: predlog je sprat najbliži izmerenom, u smeru vožnje.
        val suggested = targets.keys.filter { if (ride.up) it > from else it < from }.minByOrNull { abs(it - (from + measured)) }
        liftSteps.clear()
        val prompt = LiftPrompt(lift, from, targets.keys.sortedDescending(), suggested)
        liftPrompt = prompt
        return LiftOutcome.Ask(prompt)
    }

    /**
     * Odgovor na pitanje za lift: tačka na liftu na spratu [floor], pa se ponove koraci napravljeni dok je pitanje čekalo
     * (izašao iz lifta i hodao pre nego što je odgovorio). Vraća [PlaceReason.LIFT]; null ako pitanja nema.
     */
    fun selectLiftFloor(floor: Int): PlaceReason? {
        val prompt = liftPrompt ?: return null
        val target = graph.neighbors(prompt.lift.id)
            .firstOrNull { (other, type) -> type == EdgeType.LIFT && other.floor == floor }?.first
            ?: return null
        val pending = liftSteps.toList()
        liftPrompt = null
        liftSteps.clear()
        lastStairChange = null
        moveTo(target)
        for (s in pending) step(s.azimuthDeg, s.lengthM, s.redoSteps, s.timeNs)
        return PlaceReason.LIFT
    }

    /** "Nisam u liftu": pitanje se sklanja, tačka ostaje gde je. */
    fun dismissLift() {
        liftPrompt = null
        liftSteps.clear()
    }

    private fun clearLift() {
        liftPrompt = null
        liftSteps.clear()
    }

    /**
     * Jedan korak u pravcu [azimuthDeg] (magnetski, sa senzora). Ako [redoSteps] > 0, toliko prethodnih
     * koraka se ponavlja u tom pravcu (ispravljen smer). [timeNs]: vreme koraka (bilo koji sat) - pauza prekida let na
     * stepeništu. Vraća razlog ako je pozicija prešla na drugo mesto.
     */
    fun step(azimuthDeg: Float, lengthM: Float, redoSteps: Int = 0, timeNs: Long? = null, stairScore: Float? = null): PlaceReason? {
        var raw = raw ?: return null
        // Pitanje za lift čeka: korak se pamti (ponavlja se na izabranom spratu), a na ovom spratu ide dalje (možda nije
        // bio u liftu).
        if (liftPrompt != null) liftSteps += PendingStep(azimuthDeg, lengthM, redoSteps, timeNs)
        forgetCorridorBias(timeNs)
        walkedPathM += lengthM
        walkedInsideM += lengthM
        stepsSinceStairChange++
        lastStepM = if (climb != null || spiralWalk != null) STAIR_STEP_M else lengthM
        walkStepM = lengthM
        passage?.let { return passageStep(it, azimuthDeg, lengthM) }
        spiralWalk?.let { return spiralStep(it, azimuthDeg, lengthM, timeNs, stairScore) }
        climb?.let { return climbStep(it, azimuthDeg, redoSteps, timeNs, stairScore) }
        landingClimb?.let { c ->
            turnAtGate(c, azimuthDeg)?.let { return it }
            if (gateStep(c, azimuthDeg, lengthM)) return leaveThroughGateway()
        }
        val scale = graph.placement(place.buildingId).scale
        val matcher = matcherFor(place)
        var match = match
        val redo = redoSteps.coerceAtMost(stepStarts.size)
        if (redo > 0) {
            val from = stepStarts[stepStarts.size - redo]
            repeat(redo) { stepStarts.removeLast() }
            raw = from.raw
            match = from.match
            walkedX = from.walkedX
            walkedY = from.walkedY
        }
        val (dxM, dyM) = if (place.isCampus && headingFrozen) frozenStepVector(lengthM) else stepVector(place, azimuthDeg, lengthM)
        if (place.isCampus && !headingFrozen && timeNs != null && lengthM > 0f) {
            repeat(redo.coerceAtMost(courseSteps.size)) { courseSteps.removeLast() }
            repeat(redo + 1) { courseSteps.addLast(Triple(timeNs, Math.toDegrees(atan2(dxM, -dyM)), headingBiasDeg)) }
            while (timeNs - courseSteps.first().first > 2 * GPS_COURSE_NS) courseSteps.removeFirst()
        } else {
            courseSteps.clear()
            courseMismatch = null
        }
        val beforeMatch = match
        val before = match?.point
        if (place.isCampus) repeat(redo.coerceAtMost(campusTrail.size)) { campusTrail.removeLast() }
        repeat(redo + 1) {
            stepStarts.addLast(StepStart(raw, match, walkedX, walkedY))
            if (stepStarts.size > WalkingDirection.MAX_TURN_STEPS) stepStarts.removeFirst()
            raw = Offset(
                (raw.x + dxM / scale.widthM).toFloat().coerceIn(0f, 1f),
                (raw.y + dyM / scale.heightM).toFloat().coerceIn(0f, 1f),
            )
            match = matcher?.let { m -> match?.let { m.step(it, dxM, dyM) } ?: m.start(raw.x, raw.y) }
            walkedX += dxM
            walkedY += dyM
            if (place.isCampus) {
                stepsSinceGps++
                varianceM2 += if (headingFrozen) FROZEN_STEP_VARIANCE_M2 else STEP_VARIANCE_M2
                campusTrail.addLast(TrailStep(meters(PdrPlace.CAMPUS, raw), azimuthDeg, lengthM))
                if (campusTrail.size > DEEP_TRAIL_STEPS) campusTrail.removeFirst()
            }
        }
        this.raw = raw
        this.match = match
        if (redo == 0 && match?.free != true) learnFromCorridor(before, match?.point, dxM, dyM, lengthM) else corridorRun = 0
        if (!place.isCampus) entryCheck?.let { check -> checkEntry(check, azimuthDeg, lengthM, redo)?.let { return it } }
        // Dok je smer zamrznut, pravac iz GPS-a je pregrub za ulazak kroz vrata po pravcu koraka (19.10.2026: duž istočnog zida
        // NTP-a lažno kroz PASAŽ) - samo ulazak u obrisu.
        if (place.isCampus && headingFrozen) campusSteps.clear()
        else if (place.isCampus && lengthM > 0f) {
            campusSteps.addLast(PointM(dxM / lengthM, dyM / lengthM))
            if (campusSteps.size > ENTER_STEPS) campusSteps.removeFirst()
        }
        if (place.isCampus) clearSpiral()
        if (!place.isCampus) {
            val at = match?.let { local(it.shownX, it.shownY) } ?: local(raw.x, raw.y)
            watch.step(azimuthDeg, redo, timeNs, at)
            watch.trim(WATCH_STEPS)
            trackSpiral(azimuthDeg, redo, timeNs, at)
            if (returnToSpiral(azimuthDeg) || startSpiral(azimuthDeg, from = beforeMatch) || startSpiral(azimuthDeg)) return null
            if (startClimb(azimuthDeg)) return null
            stairTurnNearby(azimuthDeg)?.let { return it }
            val carry = spiralCarry
            if (carry != null) carrySpiral(carry, azimuthDeg, lengthM, timeNs)?.let { return it }
            else spiralNearby(azimuthDeg)?.let { return it }
        }
        if (++stepsSinceChange < MIN_STEPS_BETWEEN_CHANGES) return null
        return if (place.isCampus) enterBuilding(lengthM.toDouble() * (redo + 1)) else leaveThroughGateway()
    }

    /**
     * GPS lokacija. Napolju povlači poziciju ka sebi (jače što je tačnija, a PDR nesigurniji); bez praćenja
     * ([tracking]) pozicija je GPS lokacija. U zgradi samo potvrđuje izlazak. Vraća razlog ako je pozicija preskočila.
     */
    fun onGps(fix: GpsFix, tracking: Boolean): PlaceReason? {
        val raw = raw ?: return null
        if (passage != null) return null
        // Lokacija van mape kampusa se ne koristi: tamo tačka ne može da se prikaže, a kod kuće (proba džepa na planu
        // NTP-a, 11.10.2026) je GPS izlazak izbacivao tačku iz zgrade čim stigne prva tačna lokacija.
        if (fix.point.x !in 0.0..campus.widthM.toDouble() || fix.point.y !in 0.0..campus.heightM.toDouble()) return null
        if (!place.isCampus) {
            val detector = exitDetector ?: return null
            detector.onFix(fix)
            if (!detector.isKnown || detector.current != null) return null
            // GPS tik uz zgradu još nije izlazak: posle ulaska GPS par sekundi zaostaje ispred vrata (teren 05.10.2026, NTP:
            // ulaz -> GPS izlaz -> ulaz ... 5 puta za 20 s, "uđe ali nestane"). Kroz vrata izlazi PDR; GPS je rezerva.
            if ((campus.building(place.buildingId)?.distanceToWallM(fix.point) ?: Double.POSITIVE_INFINITY) < maxOf(GPS_EXIT_WALL_M, fix.accuracyM.toDouble())) return null
            // Detektor pamti potvrđeno "napolju" i od ranije (posle toga svaka lokacija prolazi gornji uslov) - izlazak samo uz
            // dovoljno tačnu lokaciju. Teren 10.10.2026, NTP: korisnik u holu, a prva lokacija posle Start-a (poslednja poznata,
            // tačnost 25 m) ga je izbacila napolje 1 s posle Start-a.
            // Teren 12.10.2026, NTP: korisnik na stepeništu II -> I (uz staklenu fasadu), lokacija tačnosti 19 m daleko od
            // zgrade - izlazak (i 12 s kasnije sa 11,6 m). Pravi GPS izlasci na snimcima (03.-12.10.) su imali tačnost 3-8,6 m.
            if (fix.accuracyM > min(BuildingDetector.MAX_OUTSIDE_ACCURACY_M, GPS_EXIT_MAX_ACCURACY_M)) return null
            // Napolje se izlazi samo sa sprata koji ima ulaz sa kampusa. Teren 19.10.2026 uveče, NTP S1 P -> III (uz staklenu
            // fasadu): GPS je za 30 s odlutao 25 m na zapad, sa tačnošću 9 m i bolje - izlazak sa II sprata, penjanje izgubljeno.
            if (gateways[place].orEmpty().none { it.target.type == NodeType.ULAZ && !it.target.isIndoor }) return null
            // Ni sa stepeništa (teren 21.10.2026, Kula: GPS tačnosti 3,5 m 15 m van zgrade dok je korisnik na spirali), ni
            // odmah posle ulaska ([GPS_EXIT_MIN_WALK_M]).
            if (onStairs || walkedInsideM < GPS_EXIT_MIN_WALK_M) return null
            exitByGps(fix)
            return PlaceReason.GPS_IZLAZ
        }
        if (fix.accuracyM > MAX_FUSE_ACCURACY_M) return null
        if (fix.accuracyM <= GPS_COURSE_MAX_ACCURACY_M) {
            gpsTrack.addLast(fix)
            while (gpsTrack.size > 1 && (gpsTrack.size > GPS_TRACK_MAX ||
                    fix.elapsedNs - gpsTrack.first().elapsedNs > GPS_COURSE_NS)) gpsTrack.removeFirst()
        }
        val gpsVariance = (fix.accuracyM * fix.accuracyM).toDouble()
        if (!tracking) {
            setAnchor(PdrPlace.CAMPUS, relative(PdrPlace.CAMPUS, fix.point))
            varianceM2 = gpsVariance
            return null
        }
        // Dok korisnik stoji, nova lokacija ne donosi novo znanje (greška GPS-a se sporo menja), a Kalman bi je brojao kao
        // nezavisno merenje i za par desetina sekundi preneo tačku na GPS. Teren 19.10.2026 uveče, ispred AMF-a: "Ovde sam"
        // 10 koraka ispred vrata, 15 s stajanja, GPS (tačnost 3,5 m) je tačku povukao 6 m ka vratima i ulazak je okinuo
        // ~3 koraka prerano (17.10. isto, "skoro 10 koraka"). Stojeći povlači samo lokacija tačnija od same tačke (skok GPS-a
        // se broji i dok stoji).
        val p = meters(PdrPlace.CAMPUS, raw)
        val dx = fix.point.x - p.x
        val dy = fix.point.y - p.y
        if (hypot(dx, dy) > GATE_SIGMAS * sqrt(varianceM2 + gpsVariance)) {
            if (++gpsOutliers < MAX_GPS_OUTLIERS) return null
            setAnchor(PdrPlace.CAMPUS, relative(PdrPlace.CAMPUS, fix.point))
            varianceM2 = gpsVariance
            return PlaceReason.GPS_SKOK
        }
        gpsOutliers = 0
        if (stepsSinceGps == 0 && gpsVariance >= varianceM2) return null
        stepsSinceGps = 0
        val k = varianceM2 / (varianceM2 + gpsVariance)
        this.raw = relative(PdrPlace.CAMPUS, PointM(p.x + k * dx, p.y + k * dy))
        varianceM2 *= 1 - k
        return null
    }


    /**
     * Napolju (posle GPS lokacije): za koliko stepeni okrenuti smer koraka da ide pravcem GPS-a, kad se dosledno razilaze
     * (vidi [COURSE_FIX_DEG]); null ako je u redu ili se ne zna. Posle ispravke se meri iznova (samo novi koraci).
     */
    fun courseError(poseSinceNs: Long): Double? {
        val error = courseMismatchNow(poseSinceNs)
        if (error == null) {
            courseMismatch = null
            return null
        }
        val now = gpsTrack.last().elapsedNs
        val since = courseMismatch
        if (since == null || abs(angleDeg(error - since.second)) > COURSE_AGREE_DEG) {
            courseMismatch = now to error
            return null
        }
        if (now - since.first < COURSE_HOLD_NS) return null
        courseMismatch = null
        courseSteps.clear()
        val mean = since.second + angleDeg(error - since.second) / 2
        trace?.invoke(String.format(Locale.ROOT, "smer koraka %+.0f° pored pravca GPS-a - ispravljeno", -mean))
        return mean
    }

    /** Razlika pravca GPS-a i koraka na poslednjem prozoru, samo od [poseSinceNs] (telefon tada u istom položaju). */
    private fun courseMismatchNow(poseSinceNs: Long): Double? {
        val (gpsDeg, steps) = courseWindow(poseSinceNs) ?: return null
        val stepsDeg = meanDeg(steps.map { it.second }) ?: return null
        val error = angleDeg(gpsDeg - stepsDeg)
        return error.takeIf { abs(it) > COURSE_FIX_DEG }
    }

    /**
     * Prava deonica napolju na poslednjem prozoru GPS-a, samo od [poseSinceNs]: pravac GPS-a (geografski) i koraci u njoj
     * ([courseSteps], bar [COURSE_MIN_STEPS] istog pravca); inače null.
     */
    private fun courseWindow(poseSinceNs: Long): Pair<Double, List<Triple<Long, Double, Float>>>? {
        if (!place.isCampus || headingFrozen || gpsTrack.size < 3) return null
        if (gpsTrack.any { it.accuracyM > COURSE_MAX_ACCURACY_M }) return null
        val first = gpsTrack.first()
        val last = gpsTrack.last()
        if (first.elapsedNs < poseSinceNs || last.elapsedNs - first.elapsedNs < GPS_COURSE_NS * 3 / 4) return null
        val dx = last.point.x - first.point.x
        val dy = last.point.y - first.point.y
        val d = hypot(dx, dy)
        val path = gpsTrack.zipWithNext { a, b -> hypot(b.point.x - a.point.x, b.point.y - a.point.y) }.sum()
        if (d < COURSE_CHECK_MIN_M || d < COURSE_GPS_STRAIGHT * path) return null
        val steps = courseSteps.filter { it.first in first.elapsedNs..last.elapsedNs }
        if (steps.size < COURSE_MIN_STEPS) return null
        return Math.toDegrees(atan2(dx, -dy)) to steps
    }

    /** Srednji pravac (stepeni) ako su pravci dosledni ([COURSE_STEP_CONSISTENCY]), inače null. */
    private fun meanDeg(headings: List<Double>): Double? {
        val sx = headings.sumOf { sin(Math.toRadians(it)) }
        val sy = headings.sumOf { cos(Math.toRadians(it)) }
        if (hypot(sx, sy) < COURSE_STEP_CONSISTENCY * headings.size) return null
        return Math.toDegrees(atan2(sx, sy))
    }

    /**
     * Napolju (posle GPS lokacije): na pravoj deonici koja počinje posle prethodne naučene greška smera bez ispravke (smer
     * koraka minus ispravka naspram pravca GPS-a) ulazi u ispravku napolju ([outdoorErrorDeg], vidi [OUTDOOR_BIAS_SAMPLES]).
     * Veća od [OUTDOOR_MAX_ERROR_DEG] je odstupanje telefona (džep, [courseError]), ne kompas.
     */
    fun learnOutdoorBias(poseSinceNs: Long) {
        val now = gpsTrack.lastOrNull()?.elapsedNs ?: return
        outdoorLearnedNs?.let { if (now - it > OUTDOOR_FORGET_NS || now < it) { outdoorErrors.clear(); outdoorLearnedNs = null } }
        val (gpsDeg, steps) = courseWindow(maxOf(poseSinceNs, (outdoorLearnedNs ?: Long.MIN_VALUE) + 1)) ?: return
        val raw = meanDeg(steps.map { it.second - it.third }) ?: return
        val error = angleDeg(raw - gpsDeg)
        if (abs(error) > OUTDOOR_MAX_ERROR_DEG) return
        outdoorErrors.addLast(error.toFloat())
        while (outdoorErrors.size > OUTDOOR_BIAS_SAMPLES) outdoorErrors.removeFirst()
        outdoorLearnedNs = now
        trace?.invoke(String.format(Locale.ROOT, "greška smera napolju %+.0f° (ispravka napolju %s)", error,
            outdoorErrorDeg?.let { String.format(Locale.ROOT, "%+.0f°", -it) } ?: "još ne"))
    }

    private fun angleDeg(a: Double): Double = ((a % 360.0) + 540.0) % 360.0 - 180.0

    /**
     * Korak napolju dok je smer zamrznut: pravcem kojim se GPS pomerio ([GPS_COURSE_NS], bar [GPS_COURSE_MIN_M]); bez toga
     * korak ne pomera tačku (vodi je samo GPS). Mapa kampusa je u metrima, sever gore.
     */
    private fun frozenStepVector(lengthM: Float): Pair<Double, Double> {
        val first = gpsTrack.firstOrNull() ?: return 0.0 to 0.0
        val last = gpsTrack.last()
        val dx = last.point.x - first.point.x
        val dy = last.point.y - first.point.y
        val d = hypot(dx, dy)
        if (d < GPS_COURSE_MIN_M) return 0.0 to 0.0
        return lengthM * dx / d to lengthM * dy / d
    }

    /**
     * Korak u metrima plana mesta [plan] (x udesno, y naniže); smer je ispravljen zakrenutošću zgrade u kojoj
     * je korisnik ([place]) - u prolazu se korak meri na mapi kampusa.
     */
    private fun stepVector(plan: PdrPlace, azimuthDeg: Float, lengthM: Float): Pair<Double, Double> {
        val rad = Math.toRadians((azimuthDeg + headingBiasDeg - planUp.getValue(plan.buildingId)).toDouble())
        return lengthM * sin(rad) to -lengthM * cos(rad)
    }

    private fun setAnchor(place: PdrPlace, point: Offset) {
        if (this.place.isCampus && !place.isCampus) walkedInsideM = 0.0
        this.place = place
        if (!place.isCampus) gpsTrack.clear()
        stepsSinceGps = 0
        raw = point
        match = matcherFor(place)?.start(point.x, point.y)
        passage = null
        climb = null
        spiralWalk = null
        spiralReturn = null
        spiralCarry = null
        landingClimb = null
        offFlightStair = null
        watch = StairWalk()
        corridorRun = 0
        anchorPlace = place
        anchor = meters(place, point, local = true)
        walkedX = 0.0
        walkedY = 0.0
        walkedPathM = 0.0
        pathInterrupted = false
        stepStarts.clear()
        gpsOutliers = 0
        exitDetector = if (place.isCampus) null else BuildingDetector(campus)
        stepsSinceChange = MIN_STEPS_BETWEEN_CHANGES
        pendingEntry = null
        entryCheck = null
        campusSteps.clear()
        campusTrail.clear()
    }

    /** Prelaz: pozicija na čvoru [node] (zgrada ili kampus). */
    private fun moveTo(node: Node) {
        setAnchor(node.place, Offset(node.x, node.y))
        varianceM2 = KNOWN_VARIANCE_M2
        stepsSinceChange = 0
    }

    /**
     * Greška smera: ugao od stvarnog puta (od sidra do [point]) do PDR puta (zbir koraka od sidra, sa ispravkom
     * koja je tada važila - [headingBiasDeg]). Samo na istom spratu kao sidro.
     */
    private fun checkHeading(place: PdrPlace, point: Offset): HeadingCheck? {
        val anchor = anchor ?: return null
        if (place != anchorPlace || passage != null) return null
        if (pathInterrupted) return HeadingCheck.Interrupted
        val actual = meters(place, point, local = true)
        val ax = actual.x - anchor.x
        val ay = actual.y - anchor.y
        val actualM = hypot(ax, ay)
        val walkedM = hypot(walkedX, walkedY)
        if (actualM < MIN_BIAS_DISTANCE_M || walkedM < MIN_BIAS_DISTANCE_M) {
            return HeadingCheck.TooShort(walkedM.toFloat(), actualM.toFloat())
        }
        val residual = angleDiffDeg(azimuthOf(walkedX, walkedY), azimuthOf(ax, ay))
        return HeadingCheck.Measured(
            errorDeg = angleDiffDeg(residual - headingBiasDeg, 0f),
            residualDeg = residual,
            walkedM = walkedM.toFloat(),
            actualM = actualM.toFloat(),
            reliable = actualM / walkedM in BIAS_LENGTH_RATIO && abs(residual) <= MAX_BIAS_CORRECTION_DEG &&
                walkedM >= MIN_BIAS_STRAIGHTNESS * walkedPathM,
            applied = applyCorrection,
        )
    }

    /**
     * Korak tačke od [from] do [to] na grafu sprata (korak [dxM]/[dyM], sa ispravkom smera): ako tačka već [CORRIDOR_RUN_STEPS]
     * koraka napreduje duž hodnika istog pravca, razlika smera koraka od pravca hodnika pomera [corridorBiasDeg].
     */
    private fun learnFromCorridor(from: EdgePoint?, to: EdgePoint?, dxM: Double, dyM: Double, lengthM: Float) {
        // Za vreme provere posle ulaska ispravka se ne primenjuje ([corridorBiasDeg]) - razlika tada nije merena uz nju.
        if (from == null || to == null || place.isCampus || entryCheck != null || headingFrozen || lengthM < CORRIDOR_MIN_STEP_M ||
            to.from.type in ROOM_NODE_TYPES || to.to.type in ROOM_NODE_TYPES
        ) {
            corridorRun = 0
            return
        }
        val a = local(to.from.x, to.from.y)
        val b = local(to.to.x, to.to.y)
        if (distance(a, b) < CORRIDOR_MIN_EDGE_M) {
            corridorRun = 0
            return
        }
        // Pravac ivice bez smera (0..180) i razlika koraka od njega (−90..90).
        val axis = normalizeDeg(azimuthOf(b.x - a.x, b.y - a.y)) % 180f
        val diff = angleDiffDeg(azimuthOf(dxM, dyM), axis).let { if (abs(it) > 90f) angleDiffDeg(it, 180f) else it }
        val progress = distance(local(from.x, from.y), local(to.x, to.y))
        val sameAxis = corridorAxisDeg?.let { abs(angleDiffDeg(it, axis)).let { d -> minOf(d, 180f - d) } <= CORRIDOR_LEARN_DEG / 2 } ?: false
        if (abs(diff) > CORRIDOR_LEARN_DEG || progress < 0.5 * lengthM) {
            corridorRun = 0
            corridorAxisDeg = null
            return
        }
        corridorRun = if (sameAxis) corridorRun + 1 else 1
        corridorAxisDeg = axis
        if (corridorRun < CORRIDOR_RUN_STEPS || !applyCorrection) return
        learnedCorridorBias = (learnedCorridorBias - CORRIDOR_GAIN * diff).coerceIn(-CORRIDOR_MAX_DEG, CORRIDOR_MAX_DEG)
        corridorLearnedNs = lastStepNs
    }

    // Vreme tekućeg koraka (za [corridorLearnedNs]).
    private var lastStepNs: Long? = null

    /** Posle [CORRIDOR_FORGET_NS] bez učenja ispravka iz hodnika se briše (počinje se iz zajedničke). */
    private fun forgetCorridorBias(timeNs: Long?) {
        lastStepNs = timeNs
        val learned = corridorLearnedNs ?: return
        // Starije vreme (koraci ponovljeni posle pitanja za lift) ne briše.
        if (timeNs != null && timeNs - learned > CORRIDOR_FORGET_NS) {
            learnedCorridorBias = 0f
            corridorLearnedNs = null
        }
    }

    /** Sidro i PDR put od njega (metri plana mesta sidra, bez smeštaja) - za analizu promašaja u replay-u. */
    internal class AnchorTrace(val place: PdrPlace, val anchor: PointM, val walkedX: Double, val walkedY: Double, val interrupted: Boolean)

    internal val anchorTrace: AnchorTrace?
        get() = anchor?.let { AnchorTrace(anchorPlace ?: return null, it, walkedX, walkedY, pathInterrupted) }

    /** Odluke o ulasku i prelazima, tekstom - za analizu u replay-u. */
    internal var trace: ((String) -> Unit)? = null

    /** Metri plana mesta [place] (bez smeštaja) - za analizu promašaja u replay-u. */
    internal fun localMeters(place: PdrPlace, p: Offset): PointM = meters(place, p, local = true)

    /** Metri plana mesta (bez smeštaja) čvora/tačke. */
    private fun local(x: Float, y: Float): PointM = meters(place, Offset(x, y), local = true)

    /**
     * Tačka na grafu stigla do stepeništa (ivica do čvora stepeništa, blizu njega) -> hod po stepeništu. Koraci pre toga
     * ([watch]) ostaju u hodu - prvi let je možda već počeo.
     */
    private fun startClimb(azimuthDeg: Float): Boolean {
        // Tek sišao sa stepeništa (kod dna kraka) - ne ulazi odmah opet.
        if (++stepsSinceStairExit < STAIR_REENTER_STEPS) return false
        val point = (match ?: return false).point
        offFlightStair?.let { (key, floor) ->
            val here = local(point.x, point.y)
            val away = floor != place.floor ||
                stairs[key]?.nodesOn(place.floor)?.all { distance(local(it.x, it.y), here) > OFF_FLIGHT_CLEAR_M } != false
            if (away) offFlightStair = null
        }
        sideDownFlight(point, azimuthDeg)?.let { (stair, variant, progress) ->
            // Let se broji od vrha kraka: hodnik AMF prizemlja ide istim pravcem kao bočni krak, pa bi "prvi let" sa hodom kroz
            // hodnik bio duži od [MAX_FLIGHT_STEPS] i okret na podestu se ne bi računao (teren 16.10.2026, a-m1: posle srednjeg
            // kraka "vratio se" u prizemlje).
            watch = StairWalk()
            beginClimb(stair, up = false, progress, onFlight = true, azimuthDeg, variant)
            // Smer kraka sa crteža, ne iz ovog koraka - na krak se stiže iz okreta (127-137° naspram kraka 180°), pa bi
            // sledeći korak niz krak bio "početak okreta na podestu".
            // Tačka je već na vrhu kraka (do trake nema podesta sprata), pa silazak ne čeka znak stepenica ([holdsDescent]):
            // na krakovima AMF S1 znak ostaje nizak (0-0,5), pa je tačka 20.10.2026 uveče čekala na vrhu 11 koraka ("kasnili
            // koraci na stepenicama").
            climb?.let {
                it.flightHeading = normalizeDeg(flightPlanDeg(it) - headingBiasDeg + planUp.getValue(place.buildingId))
                it.descentStarted = true
            }
            return true
        }
        val node = listOf(point.from, point.to).firstOrNull { it.type == NodeType.STEPENISTE } ?: return false
        val stair = stairs[stairKey(node)] ?: return false
        if (offFlightStair?.first == stair.key) return false
        // Spirala (Kula) se ne prati kao hod po stepeništu - sprat menja pun krug ([spiralNearby]).
        if (stair.key in SPIRAL_STAIRS) return false
        val corridor = stair.corridor(node)
        val other = if (point.from.id == node.id) point.to else point.from
        val s = local(node.x, node.y)
        val here = local(point.x, point.y)
        // Krak naviše do podesta je i HOD ivica sa stepenicima (AMF S1 sa -1: srednji krak do podesta trema ka Kuli; NB S:
        // krak do podesta prolaza ka Amfiteatrima). Ivica hodnik - stepenište je tu kratka (AMF: 0,8 m), pa je tačka preskoči
        // i ide krakom kao hodnikom - punim koracima, pravo u trem ka Kuli (teren 10.10.2026: "prelaz iz -1 na P kod
        // amfiteatara"). Tačka na kraku, korakom od dna ka podestu -> penje se krakom.
        val onFlight = other.id != corridor.id && !node.id.endsWith(DOWN_FOOT_SUFFIX) && graph.edge(node.id, other.id)?.steps == true &&
            pathsOf(stair, place.floor) != null
        var progress = 0.0
        if (onFlight) {
            val o = local(other.x, other.y)
            if (abs(angleDiffDeg(headingOnPlan(azimuthDeg), azimuthOf(o.x - s.x, o.y - s.y))) >= 90f) return false
            progress = (distance(here, s) / distance(o, s)).coerceIn(0.0, 1.0)
        } else {
            // Samo iz hodnika; preko međupodesta (iz prolaza NB - AMF ka holu) se silazi na sprat, ne ulazi u krak. Hodnik može
            // biti i sa druge strane čvora stepeništa (NTP S1-D od 16.10.2026: i iz hodnika kod lifta L1), ali ne traka ka
            // bočnom kraku (AMF S1 - tu silazak prepoznaje [sideDownFlight]).
            val from = other.takeIf { it.id == corridor.id || (it.type == NodeType.HODNIK && downChain(node, it) == null) } ?: return false
            // Dno krakova naniže koje je račvanje ka bočnim krakovima (AMF S1 od 16.10.2026, ~7 m pre krakova): silazak počinje
            // tek na vrhu kraka ([sideDownFlight]).
            if (corridorsAround(node, corridor.id).any { downChain(node, it) != null }) return false
            val c = local(from.x, from.y)
            val edge = distance(s, c)
            if (distance(here, s) > min(STAIR_ENTER_M, edge / 2)) return false
            // Samo korakom ka stepeništu - tačka koja se od njega udaljava (tek označena pored stepeništa, pa hod ka hodniku)
            // nije na kraku (teren 09.10.2026 uveče, NTP III: posle 2 koraka od S1 tačka je opet ušla na krak).
            // Ili pravcem kraka: sa podesta sprata između dva kraka (silazak preko više spratova, NTP 10.10.2026) se na krak
            // naniže stiže sa strane, ne iz hodnika.
            val heading = headingOnPlan(azimuthDeg)
            val towardStair = abs(angleDiffDeg(heading, azimuthOf(s.x - c.x, s.y - c.y)))
            val alongFlight = pathsOf(stair, place.floor)?.minOfOrNull { p ->
                val (a, b) = if (node.id.endsWith(DOWN_FOOT_SUFFIX)) p[3] to p[2] else p[0] to p[1]
                val pa = local(a.x, a.y)
                val pb = local(b.x, b.y)
                abs(angleDiffDeg(heading, azimuthOf(pb.x - pa.x, pb.y - pa.y)))
            } ?: towardStair
            if (min(towardStair, alongFlight) > STAIR_ENTER_DEG) return false
        }
        // Sa dna kraka naniže se (verovatno) silazi; putanju potvrđuje ili menja strana okreta na međupodestu.
        beginClimb(stair, up = !node.id.endsWith(DOWN_FOOT_SUFFIX), progress, onFlight, azimuthDeg)
        return true
    }

    private fun beginClimb(stair: Stair, up: Boolean, progress: Double, onFlight: Boolean, azimuthDeg: Float, variant: Int = 0) {
        trace?.invoke(String.format(Locale.ROOT, "stepenište %s: ulaz (%s, varijanta %d, krak %.2f%s)", stair.key, if (up) "gore" else "dole",
            variant, progress, if (onFlight) ", na kraku" else ""))
        val entryRaw = raw
        val entryMatch = match
        climb = Climb(stair, watch).apply {
            this.up = up
            this.variant = variant
            lastHeading = azimuthDeg
            this.progress = progress
            if (onFlight) flightHeading = azimuthDeg
            this.entryRaw = entryRaw
            this.entryMatch = entryMatch
            entryWalked = walkedX to walkedY
        }.also(::showClimb)
        watch = StairWalk()
        stepStarts.clear()
    }

    /**
     * Bočni krak naniže koji u grafu nije stepenište nego HOD ivice od dna kraka naniže do podesta (AMF S1 iz prizemlja: S1-D je
     * spoj hodnika sa oba bočna kraka, krakovi vode do podesta trema ka Kuli). Ivica hodnik - S1-D je kratka (0,5 m), tačka je
     * preskoči i ide krakom kao hodnikom, a sprat se ne menja (teren 12.10.2026: silazak P -> -1 levim krakom, pa srednjim, nije
     * prepoznat; "graf prepoznaje samo jednu stranu stepenica"). Tačka na tim ivicama, korakom niz krak -> silazi tim krakom
     * (varijanta putanje po kraku najbližem tački). Vraća stepenište, varijantu i pređeni deo kraka.
     */
    private fun sideDownFlight(point: EdgePoint, azimuthDeg: Float): Triple<Stair, Int, Double>? {
        val here = local(point.x, point.y)
        for (stair in stairs.values) {
            if (stair.buildingId != place.buildingId || stair.key == offFlightStair?.first) continue
            val foot = stair.downNodes[place.floor] ?: continue
            val variants = pathsOf(stair, place.floor) ?: continue
            if (variants.any(::isStraight) || !onDownChain(stair, foot, point)) continue
            var best: Triple<Int, Double, Double>? = null
            variants.forEachIndexed { i, p ->
                val a = local(p[3].x, p[3].y)
                val b = local(p[2].x, p[2].y)
                val len2 = (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)
                if (len2 <= 0.0) return@forEachIndexed
                val t = (((here.x - a.x) * (b.x - a.x) + (here.y - a.y) * (b.y - a.y)) / len2).coerceIn(0.0, 1.0)
                val d = distance(here, PointM(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)))
                if (best == null || d < best!!.third) best = Triple(i, t, d)
            }
            val (variant, t, _) = best ?: continue
            val p = variants[variant]
            val a = local(p[3].x, p[3].y)
            val b = local(p[2].x, p[2].y)
            // Traka uz zid do vrha kraka (AMF S1 od 16.10.2026) je deo lanca, ali još nije krak.
            if (t <= 0.0 && distance(here, a) > SIDE_FLIGHT_TOP_M) continue
            if (abs(angleDiffDeg(headingOnPlan(azimuthDeg), azimuthOf(b.x - a.x, b.y - a.y))) > STAIR_ENTER_DEG) continue
            return Triple(stair, variant, t)
        }
        return null
    }

    /** Ivica tačke je na putu od dna kraka naniže [foot] ka podestu (ne ka hodniku): HOD ivice do prve sa stepenicima. */
    private fun onDownChain(stair: Stair, foot: Node, point: EdgePoint): Boolean {
        val edge = setOf(point.from.id, point.to.id)
        return corridorsAround(foot, stair.corridor(foot).id).any { first -> downChain(foot, first)?.contains(edge) == true }
    }

    private fun corridorsAround(n: Node, except: String) =
        graph.neighbors(n.id).map { it.first }.filter { it.id != except && it.floor == n.floor && it.type == NodeType.HODNIK }

    /**
     * Ivice lanca od dna kraka [foot] preko [first] do ivice sa stepenicima (bočni krak), ili null ako to nije takav lanac. Svaki
     * bočni krak je svoj lanac: preko čvorova sa jednim nastavkom, do ivice sa stepenicima. Lanac bez nje je hodnik (NTP S3-D ima
     * dva suseda u hodniku - prva verzija je tačku iz hodnika stavila na S3, teren 12.10. ujutru).
     */
    private fun downChain(foot: Node, first: Node): List<Set<String>>? {
        fun chain(prev: Node, n: Node, acc: List<Set<String>>): List<Set<String>>? {
            val here = acc + setOf(setOf(prev.id, n.id))
            if (graph.edge(prev.id, n.id)?.steps == true) return here
            val next = corridorsAround(n, prev.id).singleOrNull() ?: return null
            return if (here.size > MAX_DOWN_CHAIN_EDGES) null else chain(n, next, here)
        }
        return chain(foot, first, emptyList())
    }

    /**
     * Let - okret - let ([StairWalk.floorTurn]) dok tačka nije na stepeništu, a prvi let je počeo do [STAIR_NEAR_M] od
     * stepeništa sa više nivoa -> korisnik je na tom stepeništu (tačka je odlutala: na snimcima je let paralelan
     * hodniku ili grani pored stepeništa - NB P -> I kroz granu prolaza ka Amfiteatrima, AMF S1 duž hodnika).
     */
    private fun stairTurnNearby(azimuthDeg: Float): PlaceReason? {
        if (watch.floorTurn(0, 0) == 0) return null
        // Tačka nije na stepeništu - let posle okreta mora biti duži nego na stepeništu (hod po holu tamo-amo pravcem krakova).
        if (watch.currentRunSteps() < NEAR_SECOND_FLIGHT_STEPS) return null
        val start = watch.flightStart() ?: return null
        fun Stair.distanceTo(p: PointM) = nodesOn(place.floor).minOf { distance(local(it.x, it.y), p) }
        // Pravo stepenište (AMF S2) nema okret - let - okret - let pored njega nije njegov.
        val stair = stairs.values
            .filter { it.buildingId == place.buildingId && place.floor in it.nodes }
            .filter { s -> pathsOf(s, place.floor)?.all(::isStraight) != true }
            // Spirala (Kula) ima svoje pravilo ([spiralNearby]); kruženje hodnikom oko jezgra Kule (strane po ~10 koraka,
            // okreti od 90°) je ovde preko pravila za širok međupodest bilo "sprat".
            .filter { it.key !in SPIRAL_STAIRS }
            .minByOrNull { it.distanceTo(start) }
            ?.takeIf { it.distanceTo(start) <= STAIR_NEAR_M }
            ?: return null
        // Stepenište sa nacrtanim krakovima: oba leta idu pravcem krakova (teren 10.10.2026, NB I sprat: hod popreko na
        // krak, petlja od ~280° po holu, pa hod u smeru kraka - bila je to lažna promena na II sprat).
        if (!flightsAlongStair(stair)) return null
        val climb = Climb(stair, watch).apply { lastHeading = azimuthDeg }
        val stairSteps = watch.stepsSinceFlightStart()
        val reason = changeFloorOnStairs(climb) ?: return null
        // Koraci od početka leta su bili na stepeništu - brojani su punom dužinom.
        lastStepM -= (lastStepM - STAIR_STEP_M) * stairSteps
        this.climb = climb
        watch = StairWalk()
        stepStarts.clear()
        showClimb(climb)
        return reason
    }

    /**
     * Spiralno stepenište ([SPIRAL_STAIRS], Kula): pun krug ([StairWalk.spiral]) koji je počeo do [SPIRAL_NEAR_M] od čvora
     * stepeništa -> sprat više (okret u smeru penjanja) ili niže; tačka na čvoru stepeništa novog sprata, dalje hod kao po
     * hodniku. Teren 18.10.2026: krakovi od 3 i 7 gazišta i okreti od 90° - let - okret - let ništa nije prepoznao, a ivica
     * hodnik - stepenište (0,6 m) je tačka preskakala, pa nije ni ušla na stepenište.
     */
    private fun spiralNearby(azimuthDeg: Float): PlaceReason? {
        val (start, sense) = spiralWatch.spiral() ?: return null
        val at = spiralWatch.positionAt(start) ?: return null
        val startPlace = spiralPlaces.getOrNull(start) ?: return null
        val stair = stairs.values.firstOrNull { s ->
            s.key in SPIRAL_STAIRS && s.buildingId == startPlace.buildingId &&
                s.nodesOn(startPlace.floor).any { distance(meters(startPlace, Offset(it.x, it.y), local = true), at) <= SPIRAL_NEAR_M }
        } ?: return null
        val overshoot = abs(spiralWatch.turnSince(start)) - 360f
        clearSpiral()
        val from = startPlace.floor
        val to = from + if (sense == SPIRAL_STAIRS.getValue(stair.key)) 1 else -1
        val node = stair.nodes[to] ?: return null
        trace?.invoke("stepenište ${stair.key}: pun krug ${if (sense > 0) "u smeru kazaljke" else "suprotno"} -> sprat $to")
        moveTo(node)
        spiralCarry = SpiralCarry(stair, sense, to, overshoot, headingOnPlan(azimuthDeg))
        lastStairChange = StairChange(stair.key, stair.buildingId, from, to, sense, guessed = false)
        stepsSinceStairChange = 0
        // Tačka je na čvoru stepeništa - sledeći korak pravcem prvog kraka ne sme odmah da je vrati na spiralu ([startSpiral]).
        stepsSinceStairExit = 0
        offFlightStair = stair.key to to
        return PlaceReason.STEPENICE
    }

    /**
     * Hod po spiralnom stepeništu ([SpiralPath], Kula): krug od četiri kraka oko okna. [ordered] su uglovi kruga u smeru hoda
     * (naviše kao u JSON-u, naniže obrnuto), [treads] gazišta po kraku u tom smeru. Kojim krakom ide, kaže okret od ulaska
     * ([turn], + = u smeru hoda: naviše u smeru kazaljke): svakih 90° sledeći krak; po kraku tačka ide gazište po korak.
     */
    private class SpiralWalk(
        val stair: Stair,
        val ordered: List<PlanPoint>,
        val treads: List<Int>,
        val up: Boolean,
        val sense: Int,
        var lastHeading: Float,
        val entryRaw: Offset?,
        val entryMatch: MatchedPosition?,
    ) {
        var turn = 0f
        var maxTurn = 0f
        var flight = 0
        var tread = 1

        // Sprat je u ovom krugu već promenjen (kraj poslednjeg kraka), pa ga skretanje u sledeći krug ne menja ponovo.
        var landed = false

        // Sprat je bar jednom promenjen (prerano skretanje na prvom kraku više nije pogrešan smer ulaska).
        var changed = false

        // Okret nazad posle promene sprata ([SPIRAL_BACK_DEG]): najmanji okret od tada - odluka čeka (nov krug ili silazak).
        var reversingMin: Float? = null

        // Koraci od početka kruga (ulaska ili podesta sprata) i PDR put tada - za [replayAsCorridor].
        val loopSteps = ArrayList<PendingStep>()

        // Znak stepenica ([StairGait]) uz [loopSteps] - za [descendedLoop].
        val loopScores = ArrayList<Float?>()
        var loopWalked: Pair<Double, Double> = 0.0 to 0.0
    }

    private var spiralWalk: SpiralWalk? = null

    /**
     * Teren 19.10.2026 (kula-up; korisnik: "tačka se nije penjala uz stepenice ... prva tri koraka bi trebala da su paralelno sa
     * smerom u kom se ide iz Kule u AMF"): stepenište je bilo samo pravougaonik, a pun krug je menjao sprat tek na kraju, dok je
     * tačka išla hodnikom (i kroz prolaz u NB). Sada: tačka do [SPIRAL_ENTER_M] od čvora stepeništa, a korak pravcem prvog kraka
     * naviše (ili prvog kraka naniže) -> hod po spirali. Prilaz stepeništu (iz hodnika ka čvoru) ide istim pravcem kao prvi krak
     * naniže, pa se smer menja ako je skretanje na prvom kraku prerano ([spiralStep]).
     */
    private fun startSpiral(azimuthDeg: Float, afterMismatch: Boolean = false, from: MatchedPosition? = match): Boolean {
        if (!afterMismatch && stepsSinceStairExit < STAIR_REENTER_STEPS) return false
        val m = from ?: return false
        val here = local(m.shownX, m.shownY)
        val heading = headingOnPlan(azimuthDeg)
        for ((key, path) in spirals) {
            val stair = stairs[key] ?: continue
            if (stair.buildingId != place.buildingId || offFlightStair == key to place.floor) continue
            val node = stair.nodes[place.floor] ?: continue
            if (distance(local(node.x, node.y), here) > SPIRAL_ENTER_M) continue
            // Samo sa ivice do stepeništa - hodnik oko lifta ide pored njega istim pravcem kao prvi krak (teren 12.10.2026 uveče:
            // hod ka severu pored stepeništa, tačka bi "ušla" na spiralu). Mesto PRE koraka ([from]) i na strani stepeništa te
            // ivice: teren 19.10.2026 uveče - "Ovde sam" na početku prvog kraka (tačka na čvoru stepeništa), a prvi korak naviše
            // (sever) je map-matching već prebacio na hodnik pored stepeništa, pa ulaska nije bilo; tačka je krakom ka istoku
            // otišla u prolaz ka NB-u. Na čvoru hodnika (kraj ivice) je hod pored stepeništa - ne ulazi.
            if (m.point.from.id != node.id && m.point.to.id != node.id) continue
            val other = if (m.point.from.id == node.id) m.point.to else m.point.from
            if (distance(local(node.x, node.y), here) > distance(local(node.x, node.y), local(other.x, other.y)) / 2) continue
            val pts = path.points
            val up = when {
                stair.nodes[place.floor + 1] != null && abs(angleDiffDeg(heading, spiralDir(pts[0], pts[1]))) <= SPIRAL_ENTER_DEG -> true
                !afterMismatch && stair.nodes[place.floor - 1] != null &&
                    abs(angleDiffDeg(heading, spiralDir(pts[pts.size - 1], pts[pts.size - 2]))) <= SPIRAL_ENTER_DEG -> false
                else -> continue
            }
            beginSpiral(key, stair, path, up, heading)
            return true
        }
        return false
    }

    /**
     * Silazak sa spirale okretom nazad na spratu (posle promene sprata): još [stepsLeft] koraka korak pravcem suprotnim od
     * poslednjeg kraka vraća na spiralu u drugom smeru. Teren 19.10.2026 uveče, Kula II: par koraka u hodnik, okret, pa nazad
     * na stepenište naniže - ulazak sa ivice stepeništa je tu bio blokiran ([offFlightStair]), a sprat je promenio tek pun krug.
     */
    private class SpiralReturn(val walk: SpiralWalk, val floor: Int, var stepsLeft: Int) {
        // Koraci posle poslednjeg gazišta (u hodnik) - isto toliko ih ima nazad do prvog gazišta.
        val pastLanding = (walk.tread - walk.treads[walk.flight.coerceIn(0, 3)]).coerceAtLeast(0)
    }

    private var spiralReturn: SpiralReturn? = null

    private fun returnToSpiral(azimuthDeg: Float): Boolean {
        val r = spiralReturn ?: return false
        val w = r.walk
        val path = spirals[w.stair.key]
        val heading = headingOnPlan(azimuthDeg)
        val last = spiralDir(w.ordered[w.ordered.size - 1], w.ordered[w.ordered.size - 2])
        if (path == null || place != PdrPlace(w.stair.buildingId, r.floor) || --r.stepsLeft < 0) {
            spiralReturn = null
            return false
        }
        if (abs(angleDiffDeg(heading, last)) > SPIRAL_ENTER_DEG) return false
        spiralReturn = null
        offFlightStair = null
        trace?.invoke("stepenište ${w.stair.key}: nazad na spiralu (${r.pastLanding} koraka do prvog gazišta)")
        beginSpiral(w.stair.key, w.stair, path, !w.up, heading)
        spiralWalk?.let { it.tread = 1 - r.pastLanding; showSpiral(it) }
        return true
    }

    /** Pravac (na planu) od [a] do [b]. */
    private fun spiralDir(a: PlanPoint, b: PlanPoint): Float {
        val pa = local(a.x, a.y)
        val pb = local(b.x, b.y)
        return azimuthOf(pb.x - pa.x, pb.y - pa.y)
    }

    private fun beginSpiral(key: String, stair: Stair, path: SpiralPath, up: Boolean, heading: Float) {
        val pts = path.points
        val upSense = SPIRAL_STAIRS[key] ?: 1
        val walk = SpiralWalk(
            stair, if (up) pts else pts.asReversed(), if (up) path.treads else path.treads.asReversed(), up,
            if (up) upSense else -upSense, heading, raw, match,
        )
        // Mesto ulaska već ima ovaj korak (hod) - ponavljaju se samo koraci posle njega.
        walk.loopWalked = walkedX to walkedY
        spiralWalk = walk
        spiralCarry = null
        stashSpiral()
        watch = StairWalk()
        stepStarts.clear()
        trace?.invoke("stepenište $key: spirala ${if (up) "naviše" else "naniže"}")
        showSpiral(walk)
    }

    /** Korak na spiralnom stepeništu: krak po okretu, gazište po korak; kraj kruga menja sprat, hod pravo dalje izlazi. */
    private fun spiralStep(w: SpiralWalk, azimuthDeg: Float, lengthM: Float, timeNs: Long?, stairScore: Float?): PlaceReason? {
        val heading = headingOnPlan(azimuthDeg)
        w.turn += angleDiffDeg(heading, w.lastHeading) * w.sense
        w.lastHeading = heading
        w.maxTurn = maxOf(w.maxTurn, w.turn)
        w.loopSteps += PendingStep(azimuthDeg, lengthM, 0, timeNs)
        w.loopScores += stairScore
        val (dx, dy) = stepVector(place, azimuthDeg, STAIR_STEP_M)
        walkedX += dx
        walkedY += dy
        // Posle promene sprata, na prvim kracima sledećeg kruga, okret nazad je ili šetanje smera telefona u mestu (teren 19.10.2026
        // uveče, Kula I: zastao i gledao Mapu, smer 3 -> 120 -> 26° kroz slabe korake, pa dalje krug naviše) ili silazak (teren
        // 20.10.2026, Kula II: odmah nazad niz spiralu - okret je pri tome prošao pravac prvog kraka, pa je ranije pravilo "nov krug"
        // tu vratilo tačku naviše, a II -> I -> P je dao samo jedan sprat). Odluka čeka: dalje nazad - silazak, opet napred - nov krug.
        val reversingMin = w.reversingMin
        if (reversingMin != null || (w.changed && !w.landed && w.turn < w.maxTurn - SPIRAL_BACK_DEG)) {
            val min = minOf(reversingMin ?: w.turn, w.turn)
            w.reversingMin = min
            val first = spiralDir(w.ordered[0], w.ordered[1])
            if (w.maxTurn - w.turn >= SPIRAL_DESCENT_BACK_DEG && w.stair.nodes[place.floor - if (w.up) 1 else -1] != null) {
                trace?.invoke("stepenište ${w.stair.key}: okret nazad posle promene sprata - silazak")
                val path = spirals.getValue(w.stair.key)
                val back = w.maxTurn - w.turn
                beginSpiral(w.stair.key, w.stair, path, !w.up, heading)
                spiralWalk?.let { d ->
                    d.changed = true
                    d.turn = back
                    d.maxTurn = back
                    d.flight = Math.floorDiv((back + 45f).toInt(), 90).coerceAtMost(3)
                    showSpiral(d)
                }
                return null
            }
            if (w.turn >= min + SPIRAL_RESUME_DEG) {
                w.reversingMin = null
                trace?.invoke("stepenište ${w.stair.key}: okret nazad posle promene sprata - nov krug")
                // Okret se meri od pravca prvog kraka, ne od ovog koraka (krak po tome gde je sada smer: 19.10. već drugi krak).
                val t = angleDiffDeg(heading, first) * w.sense
                w.turn = if (t < -45f) t + 360f else t
                w.maxTurn = w.turn
                w.flight = Math.floorDiv((w.turn + 45f).toInt(), 90).coerceIn(0, 3)
                w.tread = 1
                w.landed = false
                w.loopSteps.clear()
                w.loopScores.clear()
                w.loopWalked = walkedX to walkedY
                showSpiral(w)
                return null
            } else {
                showSpiral(w)
                return null
            }
        }
        if (w.turn < w.maxTurn - SPIRAL_BACK_DEG) {
            // Izašao na sprat posle penjanja i okreće se nazad -> nekoliko koraka sme odmah niz spiralu ([spiralReturn]). Samo posle
            // penjanja: posle silaska u prizemlje Kule korisnik je skrenuo u hodnik ka severu pored stepeništa (pravac prvog kraka
            // naviše) - to bi bilo lažno penjanje (replay 19.10. uveče).
            if (w.landed && w.up && w.stair.nodes[place.floor - 1] != null) {
                trace?.invoke("stepenište ${w.stair.key}: okret nazad na spratu")
                leaveSpiral(w)
                spiralReturn = SpiralReturn(w, place.floor, SPIRAL_RETURN_STEPS)
                returnToSpiral(azimuthDeg)
                return null
            }
            trace?.invoke("stepenište ${w.stair.key}: okret nazad - silazi sa spirale")
            if (descendedLoop(w)) return leaveSpiralBelow(w)
            return if (w.flight < 2) replayAsCorridor(w) else leaveSpiral(w)
        }
        var reason: PlaceReason? = null
        val k = Math.floorDiv((w.turn + 45f).toInt(), 90)
        if (k > w.flight) {
            // Prerano skretanje na prvom kraku: to je bio prilaz stepeništu, a ne krak (prilaz iz hodnika ide pravcem prvog kraka
            // naniže, pa skretanje ka prvom kraku naviše) - nazad gde je ušao i ponovo ulazak ovim korakom.
            if (w.flight == 0 && !w.changed && w.tread < (w.treads[0] + 1) / 2) {
                trace?.invoke("stepenište ${w.stair.key}: prerano skretanje - drugi smer")
                spiralWalk = null
                raw = w.entryRaw
                match = w.entryMatch
                if (startSpiral(azimuthDeg, afterMismatch = true)) return null
                return null
            }
            w.flight = k
            w.tread = 1
            if (w.flight >= 4) {
                if (!w.landed) reason = spiralFloor(w)
                w.flight -= 4
                w.turn -= 360f
                w.maxTurn -= 360f
                w.landed = false
                if (reason == null && spiralWalk == null) return leaveSpiral(w)
            }
        } else {
            w.tread++
            if (w.flight == 3 && w.tread > w.treads[3] && !w.landed) {
                reason = spiralFloor(w)
                w.landed = true
                if (spiralWalk == null) return leaveSpiral(w)
            }
            val overrun = when {
                w.landed -> SPIRAL_LANDING_OVERRUN_STEPS
                // Prvi krak posle promene sprata: hodnik uz stepenište ide istim pravcem (izlazak u hodnik ka severu, 18.10.).
                w.changed && w.flight == 0 -> SPIRAL_OVERRUN_AFTER_FLOOR_STEPS
                else -> SPIRAL_OVERRUN_STEPS
            }
            if (w.tread > w.treads[w.flight] + overrun) {
                trace?.invoke("stepenište ${w.stair.key}: hod pravo posle kraka - silazi sa spirale")
                if (w.landed) return leaveSpiral(w) ?: reason
                if (descendedLoop(w)) return leaveSpiralBelow(w)
                return (if (w.flight < 2) replayAsCorridor(w) else leaveSpiral(w)) ?: reason
            }
        }
        showSpiral(w)
        return reason
    }

    /**
     * Silazak bez punog okreta: krug naniže od bar tri kraka po broju koraka ([SpiralWalk.treads]), a koraci kruga po ubrzanju liče
     * na stepenice ([DESCENT_STAIR_SCORE], medijana; bar pola koraka sa znakom). Teren 27.10.2026, Kula I -> P: 22 koraka niz
     * stepenice (znak ~1,2), ali kompas uz okno je izmerio samo ~224° okreta (treći krak se "vukao" 157 -> 224°), pa je okret
     * nazad pri izlasku u hol bio "silazi sa spirale" na I spratu. Hod hodnikom oko jezgra (kula-ring) nema znak stepenica.
     */
    private fun descendedLoop(w: SpiralWalk): Boolean {
        if (w.up || w.landed || w.flight < 2 || w.loopSteps.size < w.treads.take(3).sum()) return false
        val scores = w.loopScores.filterNotNull().sorted()
        if (scores.size * 2 < w.loopScores.size) return false
        return scores[scores.size / 2] >= DESCENT_STAIR_SCORE
    }

    /** [descendedLoop]: sprat niže, pa silazak sa spirale na hodnik tog sprata. */
    private fun leaveSpiralBelow(w: SpiralWalk): PlaceReason? {
        trace?.invoke("stepenište ${w.stair.key}: krug naniže po koracima (${w.loopSteps.size}, okret ${w.maxTurn.toInt()}°)")
        val reason = spiralFloor(w)
        return leaveSpiral(w) ?: reason
    }

    /** Kraj kruga: sprat više ili niže (null ako ga nema - silazi sa spirale). */
    private fun spiralFloor(w: SpiralWalk): PlaceReason? {
        val from = place.floor
        val to = from + if (w.up) 1 else -1
        if (w.stair.nodes[to] == null) {
            spiralWalk = null
            return null
        }
        place = PdrPlace(w.stair.buildingId, to)
        w.changed = true
        spiralStash = null
        w.loopSteps.clear()
        w.loopScores.clear()
        w.loopWalked = walkedX to walkedY
        trace?.invoke("stepenište ${w.stair.key}: spirala -> sprat $to")
        lastStairChange = StairChange(w.stair.key, w.stair.buildingId, from, to, w.sense, guessed = false)
        stepsSinceStairChange = 0
        return PlaceReason.STEPENICE
    }

    /** Silazak sa spirale: tačka na najbliži hodnik sprata (od mesta na spirali); na isto stepenište ne ulazi odmah opet. */
    private fun leaveSpiral(w: SpiralWalk): PlaceReason? {
        spiralWalk = null
        stepsSinceStairExit = 0
        offFlightStair = w.stair.key to place.floor
        val r = raw ?: return null
        match = matcherFor(place)?.start(r.x, r.y, corridorOnly = true)
        clearSpiral()
        watch = StairWalk()
        return null
    }

    /**
     * Sišao sa spirale pre trećeg kraka kruga: nije se penjao, nego je hodao pored stepeništa - pravac hodnika uz stepenište
     * je isti kao prva dva kraka (teren 19.10.2026, Kula I sprat: sa podesta severno pa istočno ka prolazu u NB, kao prvi i drugi
     * krak sledećeg kruga). Tačka na početak kruga (mesto ulaska ili podest sprata), a koraci od tada se ponove kao hod.
     */
    private fun replayAsCorridor(w: SpiralWalk): PlaceReason? {
        spiralWalk = null
        stepsSinceStairExit = 0
        offFlightStair = w.stair.key to place.floor
        if (w.changed) {
            val node = w.stair.nodes.getValue(place.floor)
            raw = Offset(node.x, node.y)
            match = matcherFor(place)?.start(node.x, node.y, corridorOnly = true)
        } else {
            raw = w.entryRaw
            match = w.entryMatch
        }
        walkedX = w.loopWalked.first
        walkedY = w.loopWalked.second
        if (w.changed) clearSpiral() else restoreSpiral()
        watch = StairWalk()
        trace?.invoke("stepenište ${w.stair.key}: nije na spirali - ${w.loopSteps.size} koraka hodnikom")
        var reason: PlaceReason? = null
        for (s in w.loopSteps.toList()) step(s.azimuthDeg, s.lengthM, 0, s.timeNs)?.let { reason = it }
        return reason
    }

    /** Tačka na kraku spirale: [SpiralWalk.tread] gazišta od početka kraka (na kraju kraka - ugao - čeka okret). */
    private fun showSpiral(w: SpiralWalk) {
        val f = w.flight.coerceIn(0, 3)
        val n = w.treads[f]
        val t = w.tread.coerceIn(0, n).toFloat() / n
        val a = w.ordered[f]
        val b = w.ordered[f + 1]
        raw = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        match = null
    }

    /**
     * Letovi koje je poslednji [StairWalk.floorTurn] prepoznao paralelni su krakovima stepeništa [stair] (do
     * [FLIGHT_AXIS_DEG], u bilo kom smeru). Stepenište bez crteža krakova nema pravac - tu je uvek true.
     */
    private fun flightsAlongStair(stair: Stair, walk: StairWalk = watch, toleranceDeg: Float = FLIGHT_AXIS_DEG): Boolean {
        val path = pathsOf(stair, place.floor)?.firstOrNull() ?: return true
        val (a, b) = walk.flightHeadings ?: return true
        val from = local(path[0].x, path[0].y)
        val to = local(path[1].x, path[1].y)
        val axis = azimuthOf(to.x - from.x, to.y - from.y)
        fun parallel(heading: Float) = abs(angleDiffDeg(headingOnPlan(heading), axis)).let { it <= toleranceDeg || it >= 180 - toleranceDeg }
        return parallel(a) && parallel(b)
    }

    /** Korak na stepeništu: kratak, okret se sabira; okret na podestu menja sprat, hod ka hodniku izlazi sa stepeništa. */
    private fun climbStep(climb: Climb, azimuthDeg: Float, redoSteps: Int, timeNs: Long?, stairScore: Float?): PlaceReason? {
        climb.walk.step(azimuthDeg, redoSteps, timeNs)
        climb.scores.addLast(stairScore)
        if (climb.scores.size > GAIT_STEPS) climb.scores.removeFirst()
        climb.steps += PendingStep(azimuthDeg, walkStepM, 0, timeNs)
        val (dx, dy) = stepVector(place, azimuthDeg, STAIR_STEP_M)
        walkedX += dx
        walkedY += dy
        val path = pathOf(climb)
        if (path != null && offFlight(climb, azimuthDeg)) {
            trace?.invoke("stepenište ${climb.stair.key}: koraci van kraka - nazad u hodnik")
            return backToCorridor(climb)
        }
        if (path != null) return if (isStraight(path)) straightStep(climb, azimuthDeg) else pathStep(climb, azimuthDeg)
        climb.offsetX += dx
        climb.offsetY += dy
        val r = hypot(climb.offsetX, climb.offsetY)
        if (r > STAIR_RADIUS_M) {
            climb.offsetX *= STAIR_RADIUS_M / r
            climb.offsetY *= STAIR_RADIUS_M / r
        }
        val reason = changeFloorOnStairs(climb)
        if (reason == null && climb.walk.leaves(towardCorridorM(climb), STAIR_EXIT_M)) {
            leaveStairs(climb)
            return null
        }
        showClimb(climb)
        return reason
    }

    /** Korak na prvom kraku (pre promene sprata) i već [OFF_FLIGHT_STEPS] koraka zaredom dalje od [OFF_FLIGHT_DEG] od kraka. */
    private fun offFlight(c: Climb, azimuthDeg: Float): Boolean {
        if (c.changes > 0 || c.part != ClimbPart.FIRST || c.entryMatch == null) return false
        // Krak je (skoro) pređen - skretanje je okret na međupodestu, ne hod pored stepeništa. Do 15.10.2026 je tu stizao samo
        // posle okreta ([advanceOnPath]); kad prvi krak čeka ubrzanje ([holdsDescent]), smer kraka se meri tek na kraku i okret
        // na međupodestu (NTP 12.10.2026: 70° -> 30° -> 10°) je prvo prešao ovaj prag - tačka je vraćena u hodnik.
        if (c.progress >= FULL_FLIGHT_TURN_AT) return false
        val off =abs(angleDiffDeg(headingOnPlan(azimuthDeg), flightPlanDeg(c)))
        c.offFlight = if (off > OFF_FLIGHT_DEG) c.offFlight + 1 else 0
        return c.offFlight >= OFF_FLIGHT_STEPS
    }

    /**
     * Nije bio na kraku ([offFlight]) - hodao je hodnikom pored stepeništa: tačka se vraća gde je bila pri ulasku, a koraci od
     * tada se ponove kao hod hodnikom. Na isto stepenište ne ulazi dok se ne udalji [OFF_FLIGHT_CLEAR_M].
     */
    private fun backToCorridor(c: Climb): PlaceReason? {
        climb = null
        stepStarts.clear()
        offFlightStair = c.stair.key to place.floor
        watch = StairWalk()
        raw = c.entryRaw
        match = c.entryMatch
        walkedX = c.entryWalked.first
        walkedY = c.entryWalked.second
        var reason: PlaceReason? = null
        var walked = 0f
        for (s in c.steps) {
            step(s.azimuthDeg, s.lengthM, 0, s.timeNs)?.let { reason = it }
            walked += lastStepM
        }
        // "Pređeno": koraci na stepeništu su već brojani kao gazište, a ponovljeni su punom dužinom.
        lastStepM = walked - STAIR_STEP_M * (c.steps.size - 1)
        return reason
    }

    /**
     * Korak na stepeništu sa krakovima: tačka ide putanjom (krak, međupodest, drugi krak, podest sprata). Na kraku
     * [FLIGHT_TREADS] koraka do kraja, na podestu srazmerno okretu (meri ga žiroskop - i kad kompas promašuje). Sprat
     * menja [StairWalk] (let - okret - let), kao i bez krakova.
     */
    private fun pathStep(climb: Climb, azimuthDeg: Float): PlaceReason? {
        val turn = climb.lastHeading?.let { angleDiffDeg(azimuthDeg, it) } ?: 0f
        climb.lastHeading = azimuthDeg
        val reason = changeFloorOnStairs(climb)
        if (reason == null && advanceOnPath(climb, azimuthDeg, turn)) {
            leaveStairs(climb)
            return null
        }
        showClimb(climb)
        return reason
    }

    /**
     * Korak na pravom stepeništu (AMF S2: dva kraka u nizu, bez okreta - [StairWalk] ga ne vidi): tačka ide celom putanjom
     * ([FLIGHT_TREADS] koraka po kraku), a sprat se menja posle tri četvrtine puta. Okret nazad ili hod dalje od kraja ->
     * silazi sa stepeništa.
     */
    private fun straightStep(c: Climb, azimuthDeg: Float): PlaceReason? {
        c.lastHeading = azimuthDeg
        if (c.flightHeading == null && abs(angleDiffDeg(headingOnPlan(azimuthDeg), flightPlanDeg(c))) > NOT_ON_FLIGHT_DEG) {
            c.progress = 0.0
            leaveStairs(c)
            return null
        }
        val ref = c.flightHeading ?: azimuthDeg.also { c.flightHeading = it }
        val off = angleDiffDeg(azimuthDeg, ref)
        if (abs(off) > TURN_DONE_DEG) {
            // Okrenuo se nazad - silazi tamo gde je (na spratu na kome je tačka).
            leaveStairs(c)
            return null
        }
        if (abs(off) <= STRAIGHT_STEP_DEG) c.flightHeading = normalizeDeg(ref + off * 0.3f)
        if (c.progress >= 1.0 && ++c.straight >= EXIT_OVERFLOW_STEPS) {
            leaveStairs(c)
            return null
        }
        c.progress = (c.progress + 1.0 / (2 * treads(c))).coerceAtMost(1.0)
        var reason: PlaceReason? = null
        if (!c.changedInLeg && c.progress >= STRAIGHT_CHANGE_AT) {
            val from = place.floor
            val to = from + if (c.up) 1 else -1
            if (to in c.stair.nodes) {
                c.changedInLeg = true
                c.changes++
                place = PdrPlace(place.buildingId, to)
                lastStairChange = StairChange(c.stair.key, c.stair.buildingId, from, to, turn = 0, guessed = false)
                stepsSinceStairChange = 0
                reason = PlaceReason.STEPENICE
            }
        }
        showClimb(c)
        return reason
    }

    /** Pomera tačku po putanji stepeništa; true = korisnik silazi sa stepeništa (na mestu koje je [showClimb] postavio). */
    private fun advanceOnPath(c: Climb, heading: Float, turn: Float): Boolean {
        when (c.part) {
            ClimbPart.FIRST, ClimbPart.SECOND -> {
                // Prvi korak sa dna kraka nazad ka hodniku (stigao do stepeništa i vratio se; ili iz prolaza preko
                // međupodesta ka holu) - nije na kraku. Prag je širok: kompas u zgradi promaši i 50°.
                if (c.flightHeading == null && c.part == ClimbPart.FIRST && !onFlight(c, heading)) {
                    c.progress = 0.0
                    return true
                }
                if (holdsDescent(c)) {
                    // Još hoda po podestu sprata ka kraku (ili se okreće ka njemu): tačka čeka na vrhu kraka.
                    c.flightHeading = heading
                    return false
                }
                val ref = c.flightHeading ?: heading.also { c.flightHeading = it }
                val off = angleDiffDeg(heading, ref)
                if (abs(off) <= TURN_START_DEG) {
                    if (!c.up && (c.part == ClimbPart.SECOND || c.descentStarted)) {
                        // Silazak: koraci pravo već liče na ravan hod - sišao je pre očekivanog broja gazišta (posle drugog kraka
                        // podest sprata, hodnik; posle prvog podest sa prolazom pravo - AMF S1 trem ka Kuli, 04.10.2026); na
                        // poslednjem kraku i dalje liče na stepenice - krak je duži nego što se računa, još ne izlazi.
                        if (c.progress < 1.0 && c.progress >= DESCENT_FLAT_MIN_PROGRESS && gaitBelow(c, DESCENT_FLAT_SCORE)) {
                            c.progress = 1.0
                            c.straight = EXIT_OVERFLOW_STEPS - 1
                        } else if (c.part == ClimbPart.SECOND && c.progress >= 1.0 && c.extraTreads < DESCENT_EXTRA_TREADS &&
                            gaitAtLeast(c, DESCENT_STAIR_SCORE)
                        ) {
                            c.extraTreads++
                            return false
                        }
                    }
                    // Smer kraka prati samo korake pravo - inače bi pri postepenom okretu (NTP 05.10.2026: 15° po koraku)
                    // išao za okretom i okret nikad ne bi prešao TURN_START_DEG.
                    if (abs(off) <= STRAIGHT_STEP_DEG) c.flightHeading = normalizeDeg(ref + off * 0.3f)
                    if (c.part == ClimbPart.SECOND && !c.changedInLeg && ++c.secondSteps >= SECOND_FLIGHT_NO_CHANGE_STEPS) {
                        // Okret nije bio međupodest (vratio se istim krakom) - nazad na podest sprata sa koga je krenuo.
                        trace?.invoke("stepenište ${c.stair.key}: drugi krak bez promene sprata - vratio se")
                        c.part = ClimbPart.FIRST
                        c.progress = 0.0
                        return true
                    }
                    if (c.progress < 1.0) {
                        c.progress = (c.progress + 1.0 / treads(c)).coerceAtMost(1.0)
                        return false
                    }
                    c.straight++
                    // Na kraju prvog kraka pravo dalje u prolaz sa podesta (AMF S1: niz krak u trem ka Kuli).
                    if (c.part == ClimbPart.FIRST && c.straight >= EXIT_OVERFLOW_STEPS) {
                        gateAhead(c)?.let {
                            c.exitGate = it
                            c.exitAhead = true
                            // Okret na vratima ([turnAtGate]) se meri od smera kraka: korak izlaska je možda već deo okreta.
                            c.gateTurned = off
                            return true
                        }
                    }
                    return when (c.part) {
                        // Drugi krak je gotov, a hoda dalje pravo - podest sprata, hodnik. Krak mora da traje koliko i stvarni:
                        // NTP silazak 09.10.2026 sa 12 gazišta - postepen okret ka sledećem kraku (6-14° po koraku) je počeo
                        // posle kraja kraka, brojan je kao hod pravo i tačka je izašla sa stepeništa (I -> P nije prepoznato);
                        // sa 14 ([FLIGHT_TREADS_BY_BUILDING]) okret počinje na kraku. Strože pravilo (svaki korak do 10°)
                        // je kvarilo izlazak u hodnik NB I sprata (okret ~37° kroz 6 koraka, poslednji korak 10,4°).
                        ClimbPart.SECOND -> c.straight >= EXIT_OVERFLOW_STEPS
                        // Prvi "krak" duži od [MAX_FIRST_FLIGHT_STEPS] - hodnik pored stepeništa, nije se penjao.
                        else -> (treads(c) + c.straight >= MAX_FIRST_FLIGHT_STEPS).also { if (it) c.progress = 0.0 }
                    }
                }
                // Okret pre kraja kraka (NB S, NTP S1-S3 - krak od 12-14 gazišta, tačka na kraku je ispred korisnika): nije
                // međupodest, vratio se niz krak - nazad na dno kraka.
                if (c.part == ClimbPart.FIRST && c.changes == 0 && c.stair.key in FULL_FLIGHT_STAIRS && c.progress < FULL_FLIGHT_TURN_AT) {
                    c.progress = 0.0
                    return true
                }
                // Početak okreta: posle prvog kraka međupodest; posle drugog podest sprata ka sledećem kraku (ako se
                // okreće na istu stranu kao na međupodestu), inače silazi sa stepeništa.
                if (c.part == ClimbPart.SECOND) {
                    if (c.sense == 0 || (if (off > 0) 1 else -1) != c.sense) return true
                    c.part = ClimbPart.FLOOR_LANDING
                } else {
                    c.part = ClimbPart.LANDING
                }
                c.turned = off
                c.straight = 0
                labelDirection(c)
                c.progress = (abs(c.turned) / 180.0).coerceIn(0.0, 1.0)
                return false
            }
            ClimbPart.LANDING, ClimbPart.FLOOR_LANDING -> {
                c.turned += turn
                c.progress = (abs(c.turned) / 180.0).coerceIn(0.0, 1.0)
                if (c.part == ClimbPart.LANDING) labelDirection(c)
                // Na podestu se okreće postepeno (NB 05.10.2026: 10-20° po koraku kroz ~10 koraka) - pravo je tek
                // korak skoro bez promene smera.
                if (abs(turn) > STRAIGHT_STEP_DEG) {
                    c.straight = 0
                    return false
                }
                c.straight++
                if (abs(c.turned) >= TURN_DONE_DEG) {
                    if (c.straight < TURN_SETTLED_STEPS) return false
                    // Okret završen: sledeći krak (posle podesta sprata - krak ka sledećem spratu).
                    if (c.part == ClimbPart.LANDING) {
                        c.part = ClimbPart.SECOND
                        c.secondSteps = 1
                    } else {
                        c.part = ClimbPart.FIRST
                        c.changedInLeg = false
                    }
                    c.flightHeading = heading
                    c.progress = 1.0 / treads(c)
                    c.straight = 0
                    return false
                }
                // Hoda pravo bez punog okreta: sa podesta sprata nastavio hodnikom; sa međupodesta u prolaz, ako ga ima (NB ->
                // Amfiteatri), inače nema kuda - nije se ni penjao (okret u hodniku pored stepeništa) -> nazad na dno kraka.
                if (c.straight < LANDING_EXIT_STEPS) return false
                if (c.part == ClimbPart.LANDING) {
                    val gate = c.stair.nodes[place.floor]?.let { landingGate(c.stair, it) }.takeIf { c.up }
                    if (gate == null && abs(c.turned) >= LANDING_ACROSS_TURN_DEG && c.straight < LANDING_ACROSS_STEPS) return false
                    c.exitGate = gate
                    c.part = ClimbPart.FIRST
                    c.progress = 0.0
                } else {
                    c.walkedPastFloorLanding = true
                }
                return true
            }
        }
    }

    /**
     * Silazak, prvi krak još nije počeo: tačka je ušla u stepenište (blizu vrha kraka), a koraci po ubrzanju još nisu
     * stepenice. Analiza 15.10.2026: replay je ulazio u stepenište medijana 7-8 koraka pre nego što ubrzanje pokaže silazak
     * (do 20) - ti koraci po podestu su brojani kao gazišta. Kad [GAIT_START_STEPS] od poslednja [GAIT_STEPS] koraka liče na
     * stepenice, krak počinje (i ti koraci se računaju kao gazišta - znak kasni). Bez znaka (džep, nema baze) ili posle
     * [DESCENT_HOLD_STEPS] koraka - kao ranije.
     */
    private fun holdsDescent(c: Climb): Boolean {
        if (c.up || c.part != ClimbPart.FIRST || c.changes > 0 || c.descentStarted) return false
        val recent = c.scores.toList()
        if (c.progress > 0.0 || recent.isEmpty() || recent.last() == null || ++c.heldSteps > DESCENT_HOLD_STEPS) {
            c.descentStarted = true
            return false
        }
        val stairLike = recent.count { it != null && it >= DESCENT_START_SCORE }
        if (stairLike < GAIT_START_STEPS) return true
        c.descentStarted = true
        // Ovaj korak doda još jedno gazište (dalji tok koraka).
        c.progress = (stairLike - 1).toDouble() / treads(c)
        return false
    }

    /** Poslednja [GAIT_STEPS] koraka imaju znak stepenica, a medijana je ispod [limit]. */
    private fun gaitBelow(c: Climb, limit: Float): Boolean = gaitMedian(c)?.let { it < limit } == true

    private fun gaitAtLeast(c: Climb, limit: Float): Boolean = gaitMedian(c)?.let { it >= limit } == true

    private fun gaitMedian(c: Climb): Float? {
        if (c.scores.size < GAIT_STEPS || c.scores.any { it == null }) return null
        return c.scores.map { it!! }.sorted()[GAIT_STEPS / 2]
    }

    /**
     * Gazišta kraka kojim tačka sada ide: po stepeništu i kraku ([FLIGHT_TREADS_BY_STAIR]), inače po zgradi, inače
     * [FLIGHT_TREADS]. "Donji" krak je deo putanje od dna kraka naviše do međupodesta (sa sprata naviše), "gornji" od
     * međupodesta do sprata iznad.
     */
    private fun treads(c: Climb): Int {
        val lower = c.up == (c.part == ClimbPart.FIRST)
        return FLIGHT_TREADS_BY_STAIR[c.stair.key]?.invoke(place.floor, lower)
            ?: FLIGHT_TREADS_BY_BUILDING[c.stair.buildingId] ?: FLIGHT_TREADS
    }

    /**
     * Prvi korak sa dna kraka ide na krak: do [NOT_ON_FLIGHT_DEG] od smera kraka i bliže smeru kraka nego smeru ka hodniku.
     * Teren 09.10.2026 uveče, NTP III: "Ovde sam" pored S1, pa hod ka hodniku ~90° od kraka (kompas +7°) - prag od 100° je
     * to pustio na krak, tačka je išla stepenicama, korisnik je dvaput ponovo pokrenuo praćenje.
     */
    private fun onFlight(c: Climb, heading: Float): Boolean {
        val onPlan = headingOnPlan(heading)
        val toFlight = abs(angleDiffDeg(onPlan, flightPlanDeg(c)))
        if (toFlight > NOT_ON_FLIGHT_DEG) return false
        val foot = (if (c.up) c.stair.nodes[place.floor] else c.stair.downNodes[place.floor]) ?: return true
        val a = local(foot.x, foot.y)
        val b = c.stair.corridor(foot).let { local(it.x, it.y) }
        if (distance(a, b) < 0.1) return true
        return toFlight <= abs(angleDiffDeg(onPlan, azimuthOf(b.x - a.x, b.y - a.y)))
    }

    /** Smer prvog kraka na planu (0 = gore, u smeru kazaljke): naviše od dna kraka naviše, naniže od dna kraka naniže. */
    private fun flightPlanDeg(c: Climb): Float {
        val (a, b) = firstFlight(c) ?: return 0f
        return azimuthOf(b.x - a.x, b.y - a.y)
    }

    /** Prvi krak hoda [c] u metrima plana: (dno, vrh) - naviše od dna kraka naviše, naniže od dna kraka naniže. */
    private fun firstFlight(c: Climb): Pair<PointM, PointM>? {
        val path = pathOf(c) ?: return null
        val (a, b) = if (c.up) path[0] to path[1] else path[3] to path[2]
        return local(a.x, a.y) to local(b.x, b.y)
    }

    /**
     * Prolaz sa podesta na kraju prvog kraka, pravo ispred (AMF S1: trem ka Kuli ispod krakova): do 0,5 m iza kraja
     * kraka i najviše 3,5 m u stranu. NB prolaz ka AMF-u je sa strane međupodesta - do njega se skreće ([landingGate]).
     */
    private fun gateAhead(c: Climb): Pair<Node, Node>? {
        val start = (if (c.up) c.stair.nodes[place.floor] else c.stair.downNodes[place.floor]) ?: return null
        val gate = landingGate(c.stair, start) ?: return null
        val (a, b) = firstFlight(c) ?: return null
        val length = distance(a, b).takeIf { it > 0 } ?: return null
        val ux = (b.x - a.x) / length
        val uy = (b.y - a.y) / length
        val g = local(gate.second.x, gate.second.y)
        val ahead = (g.x - b.x) * ux + (g.y - b.y) * uy
        val aside = abs((g.x - b.x) * uy - (g.y - b.y) * ux)
        return gate.takeIf { ahead >= -0.5 && aside <= 3.5 }
    }

    /**
     * Na međupodestu, pre promene sprata: strana okreta po crtežu krakova kaže kojim krakom je išao (penjanje se okreće
     * na stranu [pathUpTurns]) - ako je ušao na drugi krak nego što se mislilo, tačka prelazi na njega. Stepenište sa dva
     * nivoa (AMF S1): smer je poznat (sa najnižeg gore, sa najvišeg dole), a strana okreta bira bočni krak.
     */
    private fun labelDirection(c: Climb) {
        if (c.changes > 0 || abs(c.turned) < TURN_START_DEG) return
        val turn = if (c.turned > 0) 1 else -1
        val canUp = (place.floor + 1) in c.stair.nodes
        val canDown = (place.floor - 1) in c.stair.nodes
        c.up = if (canUp && canDown) pathUpTurns[c.stair.key]?.let { turn == it } ?: c.up else canUp
        selectVariant(c, if (c.up) turn else -turn)
    }

    /**
     * Okret od [STAIR_TURN_DEG] na jednu stranu (pa svakih sledećih [STAIR_TURN_PER_FLOOR_DEG]) -> sprat više ili niže,
     * po strani okreta. Vraća [PlaceReason.STEPENICE] ako je sprat promenjen.
     */
    private fun changeFloorOnStairs(climb: Climb): PlaceReason? {
        val sense = climb.walk.floorTurn(climb.changes, climb.sense, onStair = true).takeIf { it != 0 } ?: return null
        val stair = climb.stair
        // Prva promena i na samom stepeništu traži letove pravcem krakova (kao pored njega, [stairTurnNearby]). Teren
        // 12.10.2026 (posle zabrane grane lifta tačka iz hola NB I sprata stiže do stepeništa): hod hodnikom pa petlja od
        // ~280° po holu, pa korak ka kraku - "let" pre okreta je bio popreko na krakove (81°). Na stepeništu je prag širi:
        // 03.10.2026 NB P -> I kompas u stepeništu ~55° pored kraka (drugi let 122° umesto 180°), a to je pravi prelaz.
        if (climb.changes == 0 && !flightsAlongStair(stair, climb.walk, ON_STAIR_FLIGHT_AXIS_DEG)) return null
        val from = place.floor
        val up = (from + 1) in stair.nodes
        val down = (from - 1) in stair.nodes
        val known = pathUpTurns[stair.key] ?: learnedUpTurns[stair.key] ?: STAIR_UP_TURN[stair.key]
        val hint = destination?.takeIf { it.buildingId == stair.buildingId && it.floor != from }?.floor
        // Sa najnižeg/najvišeg nivoa smer je poznat - i uči se strana okreta (ako se već ne zna; stepeništa sa dva nivoa
        // ne treba učiti).
        val learn = stair.nodes.size > 2 && known == null
        val step = when {
            up && !down -> 1.also { if (learn) learnedUpTurns[stair.key] = sense }
            down && !up -> (-1).also { if (learn) learnedUpTurns[stair.key] = -sense }
            !up -> return null
            known != null -> if (known == sense) 1 else -1
            hint != null -> if (hint > from) 1 else -1
            else -> 1
        }
        climb.walk.confirmChange()
        climb.sense = sense
        climb.changes++
        // Na novom spratu hod se meri od čvora stepeništa: drugi let se vraća ka podestu sprata, pa bi stari hod
        // (do 3 m) mogao odmah da izgleda kao izlazak u hodnik.
        climb.offsetX = 0.0
        climb.offsetY = 0.0
        // Sa krakovima: tačka na drugom kraku (već sprat iznad/ispod), koliko je koraka njime prešao.
        climb.up = step > 0
        selectVariant(climb, if (climb.up) sense else -sense)
        climb.part = ClimbPart.SECOND
        climb.changedInLeg = true
        climb.secondSteps = climb.walk.currentRunSteps()
        climb.flightHeading = climb.lastHeading
        climb.straight = 0
        place = PdrPlace(place.buildingId, from + step)
        // Posle promene sprata - broj gazišta zavisi od kraka na novom spratu (NTP S1: prvi krak iz prizemlja 13).
        climb.progress = (climb.secondSteps.toDouble() / treads(climb)).coerceAtMost(1.0)
        lastStairChange = StairChange(stair.key, stair.buildingId, from, from + step, sense, guessed = up && down && known == null)
        stepsSinceStairChange = 0
        return PlaceReason.STEPENICE
    }

    /** Hod na stepeništu (od čvora) u pravcu hodnika, u metrima. */
    private fun towardCorridorM(climb: Climb): Double {
        val s = climb.stair.nodes.getValue(place.floor)
        val c = climb.stair.corridors.getValue(place.floor)
        val a = local(s.x, s.y)
        val b = local(c.x, c.y)
        val length = distance(a, b).takeIf { it > 0 } ?: return 0.0
        return (climb.offsetX * (b.x - a.x) + climb.offsetY * (b.y - a.y)) / length
    }

    /**
     * Pozicija na stepeništu. Sa krakovima: tačka na putanji (penjanje od dna kraka naviše, silazak od dna kraka naniže),
     * bez lepljenja na graf. Bez krakova: na ivici stepenište - hodnik, koliko je hodao ka hodniku; slobodna = čvor + hod.
     */
    private fun showClimb(climb: Climb) {
        val path = pathOf(climb)
        if (path != null) {
            val ordered = if (climb.up) path else path.asReversed()
            if (isStraight(path)) {
                val scale = graph.placement(place.buildingId).scale
                raw = pointAlong(ordered, climb.progress, scale.widthM.toDouble(), scale.heightM.toDouble())
                match = null
                return
            }
            // Drugi krak je gotov, a hoda dalje: dok se izlazak ne potvrdi ([EXIT_OVERFLOW_STEPS]), tačka već ide ka hodniku -
            // tamo je i stavlja izlazak. Teren 13.10.2026, NB II -> III: "malo je zakasnelo prešao sa stepenica na ravni deo,
            // nekih 3 koraka kasnije" - tačka je stajala na vrhu kraka.
            afterFlight(climb)?.let {
                raw = it
                match = null
                return
            }
            val (a, b, c, d) = ordered
            val (from, to) = when (climb.part) {
                ClimbPart.FIRST -> a to b
                ClimbPart.LANDING -> b to c
                ClimbPart.SECOND -> c to d
                ClimbPart.FLOOR_LANDING -> d to a
            }
            val t = climb.progress.toFloat()
            raw = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
            match = null
            return
        }
        val s = climb.stair.nodes.getValue(place.floor)
        val c = climb.stair.corridors.getValue(place.floor)
        val length = distance(local(s.x, s.y), local(c.x, c.y))
        val t = if (length > 0) (towardCorridorM(climb) / length).coerceIn(0.0, 1.0) else 0.0
        val scale = graph.placement(place.buildingId).scale
        val free = Offset(
            (s.x + climb.offsetX / scale.widthM).toFloat().coerceIn(0f, 1f),
            (s.y + climb.offsetY / scale.heightM).toFloat().coerceIn(0f, 1f),
        )
        raw = free
        match = MatchedPosition(EdgePoint(s, c, t), free.x, free.y)
    }

    /** Sa stepeništa u hodnik: od tačke na stepeništu (kraj kraka / ivica stepenište - hodnik) dalje map-matching sprata. */
    private fun leaveStairs(climb: Climb) {
        trace?.invoke(String.format(Locale.ROOT, "stepenište %s: izlaz (%s, krak %.2f, promena %d)", climb.stair.key, climb.part, climb.progress, climb.changes))
        this.climb = null
        stepStarts.clear()
        stepsSinceStairExit = 0
        // Bez promene sprata koraci ostaju za sledeći ulazak (let pre okreta); posle promene ne - isti okret bi se brojao
        // još jednom.
        if (climb.changes == 0) watch = climb.walk.detached()
        // Sa podesta sprata je otišao pravo, pored dna sledećeg kraka - ne ulazi odmah na njega. Teren 13.10.2026, NTP II (i
        // 12.10. uveče): silazak III -> II, pa ~8 koraka pored S1-D ka hodniku i istočno ka L1 - pravac sledećeg kraka naniže,
        // pa je tačka posle 3 koraka opet ušla na krak ("nije otišao na L1, nego je krenuo na stepenice").
        if (climb.walkedPastFloorLanding) offFlightStair = climb.stair.key to place.floor
        climb.exitGate?.let { (inner, gate) ->
            // Na kraju puta preko međupodesta, na vratima prolaza - sledeći koraci napolje prelaze prolaz.
            raw = Offset(gate.x, gate.y)
            match = MatchedPosition(EdgePoint(inner, gate, 1.0), gate.x, gate.y)
            if (climb.exitAhead) landingClimb = climb
            return
        }
        showClimb(climb)
        val point = afterFlight(climb) ?: match?.point?.let { Offset(it.x, it.y) } ?: raw!!
        raw = point
        match = matcherFor(place)?.start(point.x, point.y)
    }

    /**
     * Silazi posle drugog kraka (hodao je pravo dalje - podest sprata, hodnik): tačka na ivici dno kraka - hodnik, koliko je
     * tim koracima prešao, a ne na dnu kraka. Analiza promašaja 12.10.2026, NTP II 08.10.: tačka je ostala na čvoru S2, korisnik
     * je skrenuo hodnikom ka liftovima (korak popreko na ivicu do hodnika - bez napretka), pa je posle 3 koraka opet ušla na
     * krak i tu ostala (25 m promašaja). null: nije taj slučaj.
     */
    private fun afterFlight(climb: Climb): Offset? {
        // Penjanje bočnim krakom AMF S1 izlazi na račvanje S1-D, ne na vrh kraka: put trakom od vrha kraka (8 m u grafu) je
        // proban 20.10.2026 uveče i bio je gori - 19.10. i 20.10. je korisnik od vrha kraka do oznake u hodniku prešao 4-5 m
        // manje nego što graf trakom kaže (promašaj 1,4 -> 3,7 m i 2,4 -> 5,5 m).
        if (climb.part != ClimbPart.SECOND || climb.progress < 1.0 || climb.straight <= 0 || pathOf(climb) == null) return null
        val foot = (if (climb.up) climb.stair.downNodes[place.floor] else climb.stair.nodes[place.floor]) ?: return null
        val corridor = climb.stair.corridor(foot)
        val length = distance(local(foot.x, foot.y), local(corridor.x, corridor.y)).takeIf { it > 0 } ?: return null
        val t = (climb.straight * walkStepM / length).coerceIn(0.0, 1.0).toFloat()
        return Offset(foot.x + (corridor.x - foot.x) * t, foot.y + (corridor.y - foot.y) * t)
    }

    /**
     * Prolaz sa međupodesta stepeništa [stair] na trenutnom spratu (NB prizemlje -> Amfiteatri): od dna kraka naviše
     * putem koji ne ide u hodnik do čvora prolaza. Vraća (čvor pre prolaza, čvor prolaza).
     */
    private fun landingGate(stair: Stair, foot: Node): Pair<Node, Node>? {
        val gates = gateways[place].orEmpty().map { it.node.id }.toSet()
        // Put preko podesta ide hodnikom (ne kroz vrata i sale): AMF S1 od 16.10.2026 ima traku do kraka i vrata Čitaonice uz nju.
        fun onWay(n: Node) = n.floor == foot.floor && n.buildingId == foot.buildingId && n.type != NodeType.VRATA && n.type != NodeType.PROSTORIJA
        var prev = foot
        var current = graph.neighbors(foot.id).map { it.first }
            .firstOrNull { onWay(it) && it.id != stair.corridor(foot).id } ?: return null
        repeat(LANDING_GATE_HOPS) {
            if (current.id in gates) return prev to current
            val next = graph.neighbors(current.id).map { it.first }
                .firstOrNull { it.id != prev.id && onWay(it) } ?: return null
            prev = current
            current = next
        }
        return null
    }

    /**
     * Ručno označavanje ubrzo posle promene sprata na stepeništu: na koju stranu je stvarno išao (gore ili dole od
     * sprata sa koga je krenuo) -> strana okreta pri penjanju za to stepenište.
     */
    private fun learnStairTurn(place: PdrPlace) {
        val change = lastStairChange ?: return
        lastStairChange = null
        if (stepsSinceStairChange > STAIR_LEARN_STEPS || place.buildingId != change.buildingId) return
        if ((stairs[change.stairKey]?.nodes?.size ?: 0) <= 2 || change.stairKey in pathUpTurns) return
        val actual = place.floor.compareTo(change.fromFloor)
        if (actual != 0) learnedUpTurns[change.stairKey] = if (actual > 0) change.turn else -change.turn
    }

    /** Napolju: tačka duboko u obrisu zgrade sa planom, blizu njenog ulaza -> u zgradu, na taj ulaz. */
    /**
     * Napolju posle koraka ([stepM]: dužina koraka, uz ponovljene): tačka u obrisu zgrade sa planom blizu njenog ulaza ->
     * ulazak, ali tek kad koraci pređu put koji je tačka tada imala do ulaza ([PendingEntry]). Pređeni put je zbir dužina
     * koraka, ne deo u pravcu ulaza: tačka koja uđe u obris pravo kroz vrata je već iza njih, a tačka koju GPS drži bočno
     * od vrata ide pored njih (teren 08.10.2026, NB: tačka 5 m bočno, korisnik ide popreko na zid ka vratima).
     */
    private fun enterBuilding(stepM: Double): PlaceReason? {
        val p = meters(PdrPlace.CAMPUS, raw ?: return null)
        rejectedEntrance?.let { if (distance(graph.position(it), p) > ENTER_RADIUS_M) rejectedEntrance = null }
        exitedEntrance?.let { if (distance(graph.position(it), p) > ENTER_CANCEL_M) exitedEntrance = null }
        enterFromDeepInside(p)?.let { return it }
        pendingEntry?.let { pending ->
            val inward = pending.entrance.inward
            val last = campusSteps.lastOrNull()
            pending.walkedM += if (pending.byDirection && inward != null && last != null) {
                stepM * maxOf(0.0, last.x * inward.x + last.y * inward.y)
            } else {
                stepM
            }
            val slack = if (pending.byDirection) 0.0 else ENTER_SLACK_M
            // Ne kroz vrata korakom ka spolja: teren 22.10.2026, glavni ulaz NTP-a - GPS i tačka su korisnika uz zid držali
            // ~3 m u obrisu, ulazak je čekao put do vrata, a korisnik je tada išao uz zid (pa i ka spolja); ušao je ~10 koraka
            // kasnije, okretom ka hodniku - posle lažnog ulaska tačka je izašla, pa ušla duboko u obrisu, ispred njega.
            val outward = inward != null && last != null && last.x * inward.x + last.y * inward.y < 0
            if (pending.walkedM >= pending.remainingM - slack && !outward) return enter(pending.entrance)
            if (distance(graph.position(pending.entrance.outside), p) <= ENTER_CANCEL_M) return null
            pendingEntry = null
        }
        // Dok je smer zamrznut, tačku vodi GPS, a on korisnika uz zid drži i nekoliko metara u obrisu (19.10.2026, istočni zid
        // NTP-a: lažno kroz PASAŽ i sa 4 m dubine) - ulazi se samo duboko u obrisu ([enterFromDeepInside]).
        for ((buildingId, list) in entrances) {
            val outline = campus.building(buildingId) ?: continue
            if (headingFrozen || !outline.contains(p) || outline.distanceToWallM(p) < ENTER_DEPTH_M) continue
            val entrance = list.filter { it.outside.id != rejectedEntrance?.id && it.outside.id != exitedEntrance?.id }
                .minByOrNull { distance(graph.position(it.outside), p) } ?: continue
            val toDoor = distance(graph.position(entrance.outside), p)
            if (toDoor > ENTER_RADIUS_M) continue
            trace?.invoke(String.format(Locale.ROOT, "ulaz u obrisu: %s, do vrata %.1f m", entrance.outside.id, toDoor))
            if (toDoor <= ENTER_SLACK_M) return enter(entrance)
            pendingEntry = PendingEntry(entrance, toDoor)
            return null
        }
        // Tačka van obrisa (GPS je vuče uz zid), ali koraci idu kroz vrata ka unutra.
        if (campusSteps.size < ENTER_STEPS) return null
        for (entrance in entrances.values.flatten()) {
            if (entrance.outside.id == rejectedEntrance?.id) continue
            val inward = entrance.inward ?: continue
            val toDoor = distance(graph.position(entrance.outside), p)
            if (toDoor > ENTER_STEPS_NEAR_M) continue
            val minCos = cos(Math.toRadians(if (entrance.outside.id == exitedEntrance?.id) EXITED_ENTER_STEP_ANGLE_DEG else ENTER_STEP_ANGLE_DEG))
            if (campusSteps.any { it.x * inward.x + it.y * inward.y < minCos }) continue
            trace?.invoke(String.format(Locale.ROOT, "ulaz po pravcu koraka: %s, do vrata %.1f m", entrance.outside.id, toDoor))
            pendingEntry = PendingEntry(entrance, toDoor, byDirection = true)
            return null
        }
        return null
    }

    /** Tačka napolju nesumnjivo duboko u zgradi ([DEEP_INSIDE_M]) -> na najbliži hodnik sprata ulaza te zgrade. */
    private fun enterFromDeepInside(p: PointM): PlaceReason? {
        val (buildingId, list) = entrances.entries.firstOrNull { (id, _) ->
            campus.building(id)?.let { it.contains(p) && it.distanceToWallM(p) >= DEEP_INSIDE_M } == true
        } ?: run {
            deepSteps = 0
            return null
        }
        if (++deepSteps < DEEP_STEPS) return null
        deepSteps = 0
        val trail = campusTrail.toList()
        val via = list.mapNotNull { entrance -> entryAlongTrail(entrance, trail, p) }.minByOrNull { it.second }
        if (via != null) {
            val (entrance, _, after) = via
            moveTo(entrance.inside)
            for (s in after) walkIndoor(s.azimuthDeg, s.lengthM)
            return PlaceReason.ULAZ
        }
        val floor = list.minOf { it.inside.floor }
        val place = PdrPlace(buildingId, floor)
        setAnchor(place, relative(place, p))
        // Na hodnik, ne na granu sale (matcher sa hodnika u salu ne ide - tačka bi ostala u sali).
        match = matcherFor(place)?.start(raw!!.x, raw!!.y, corridorOnly = true)
        varianceM2 = KNOWN_VARIANCE_M2
        stepsSinceChange = 0
        return PlaceReason.ULAZ
    }

    /**
     * Trag koraka napolju [trail] je prošao ulazu [entrance] najbliže do [DEEP_ENTRY_NEAR_M], a hod od tog mesta do [p]
     * ide ka unutra -> (ulaz, najmanje rastojanje, koraci posle tog mesta). Inače null.
     */
    private fun entryAlongTrail(entrance: Entrance, trail: List<TrailStep>, p: PointM): Triple<Entrance, Double, List<TrailStep>>? {
        if (trail.isEmpty()) return null
        val inward = entrance.inward ?: return null
        val door = graph.position(entrance.outside)
        val i = trail.indices.minBy { distance(trail[it].at, door) }
        val nearest = distance(trail[i].at, door)
        if (nearest > DEEP_ENTRY_NEAR_M) return null
        val net = PointM(p.x - trail[i].at.x, p.y - trail[i].at.y)
        val netM = hypot(net.x, net.y)
        if (netM < 1.0 || (net.x * inward.x + net.y * inward.y) / netM < cos(Math.toRadians(DEEP_ENTRY_INWARD_DEG))) return null
        return Triple(entrance, nearest, trail.drop(i + 1))
    }

    /** Korak u zgradi samo kroz map-matching (bez prelaza i stepeništa) - za korake posle ulaska koji su već napravljeni. */
    private fun walkIndoor(azimuthDeg: Float, lengthM: Float) {
        val scale = graph.placement(place.buildingId).scale
        val (dxM, dyM) = stepVector(place, azimuthDeg, lengthM)
        val raw = raw ?: return
        this.raw = Offset(
            (raw.x + dxM / scale.widthM).toFloat().coerceIn(0f, 1f),
            (raw.y + dyM / scale.heightM).toFloat().coerceIn(0f, 1f),
        )
        val matcher = matcherFor(place)
        match = matcher?.let { m -> match?.let { m.step(it, dxM, dyM) } ?: m.start(this.raw!!.x, this.raw!!.y) }
        walkedX += dxM
        walkedY += dyM
    }

    private fun enter(entrance: Entrance): PlaceReason {
        moveTo(entrance.inside)
        entryCheck = EntryCheck(entrance)
        return PlaceReason.ULAZ
    }

    /**
     * Posle ulaska: korisnik koji stvarno uđe odmiče se od ulaza po grafu. Ako je posle [ENTRY_CHECK_MIN_M] pomeraja tačka
     * na grafu i dalje kod ulaza, a koraci ga vode uz zid ili napolje (ne duboko u obris) - ulazak je bio prerani: nazad
     * na kampus, na ulaz + pomeraj koraka.
     */
    private fun checkEntry(check: EntryCheck, azimuthDeg: Float, lengthM: Float, redo: Int): PlaceReason? {
        val (sx, sy) = stepVector(PdrPlace.CAMPUS, azimuthDeg, lengthM)
        repeat(redo.coerceAtMost(check.steps.size)) { check.steps.removeAt(check.steps.lastIndex) }
        repeat(redo + 1) { check.steps += PointM(sx, sy) }
        check.walkedM += lengthM * (redo + 1.0)
        if (check.walkedM > ENTRY_CHECK_MAX_M) {
            entryCheck = null
            return null
        }
        val net = PointM(check.steps.sumOf { it.x }, check.steps.sumOf { it.y })
        val netM = hypot(net.x, net.y)
        if (netM < ENTRY_CHECK_MIN_M) return null
        val shown = match?.let { Offset(it.shownX, it.shownY) } ?: raw ?: return null
        if (distance(meters(place, shown), graph.position(check.entrance.inside)) >= ENTRY_PROGRESS_RATIO * netM) return null
        val door = graph.position(check.entrance.outside)
        val walked = PointM(door.x + net.x, door.y + net.y)
        val inward = check.entrance.inward
        if (inward != null) {
            // Hod u zgradu ide u pravcu ulaza ka hodniku; uz zid je skoro popreko na njega.
            if ((net.x * inward.x + net.y * inward.y) / netM > cos(Math.toRadians(ENTRY_ALONG_WALL_DEG))) return null
        } else {
            val outline = campus.building(check.entrance.inside.buildingId)
            if (outline != null && outline.contains(walked) && outline.distanceToWallM(walked) >= ENTER_DEPTH_M) return null
        }
        setAnchor(PdrPlace.CAMPUS, relative(PdrPlace.CAMPUS, walked))
        rejectedEntrance = check.entrance.outside
        varianceM2 = KNOWN_VARIANCE_M2
        stepsSinceChange = 0
        return PlaceReason.NIJE_USAO
    }

    /** U zgradi: tačka na grafu na kraju hodnika (ulaz/prolaz), a korisnik ide dalje napolje -> prolaz. */
    private fun leaveThroughGateway(): PlaceReason? {
        val match = match ?: return null
        val point = match.point
        val scale = graph.placement(place.buildingId).scale
        fun m(x: Float, y: Float) = PointM(x * scale.widthM.toDouble(), y * scale.heightM.toDouble())
        val onEdge = m(point.x, point.y)
        val free = m(match.x, match.y)
        for (gate in gateways[place].orEmpty()) {
            val other = when (gate.node.id) {
                point.from.id -> point.to
                point.to.id -> point.from
                else -> continue
            }
            val end = m(gate.node.x, gate.node.y)
            if (distance(onEdge, end) > AT_GATE_M) continue
            val inner = m(other.x, other.y)
            val beyond = along(free, end, inner, end)
            if (beyond < PASS_BEYOND_M) continue
            // Pravac hodnika ka izlazu, u metrima kampusa.
            val start = graph.position(gate.node)
            val back = graph.position(other)
            val length = distance(back, start)
            val outward = PointM((start.x - back.x) / length, (start.y - back.y) / length)
            val resume = landingClimb?.takeIf { it.exitGate?.second?.id == gate.node.id }
            val target = targetOf(gate)
            val passage = Passage(place, target, start, graph.position(target), outward, beyond, resume)
            trace?.invoke("prolaz ${gate.node.id} -> ${target.id}${if (resume != null) " (sa međupodesta)" else ""}")
            landingClimb = null
            this.passage = passage
            stepStarts.clear()
            return updatePassage(passage)
        }
        return null
    }

    /**
     * Povratak iz prolaza na međupodest ([Passage.resume]) - međupodest pripada oba nivoa zgrade (korisnik, teren 10.10.2026:
     * "treba dodati taj međusprat da bude deo amfiteatara, na oba nivoa"). AMF S1: sa -1 srednjim krakom na podest, nekoliko
     * koraka trem ka Kuli, pa okret nazad - to je okret na podestu, dalje bočnim krakom u prizemlje (pre toga tačka je
     * vraćana na -1). Isti smer kao pre prolaza (došao odozdo -> gore, odozgo -> dole); strana okreta u prolazu bira krak.
     */
    private fun resumeOnLanding(c: Climb, turned: Float): PlaceReason? {
        val from = place.floor
        val to = from + if (c.up) 1 else -1
        if (to !in c.stair.nodes && to !in c.stair.downNodes) return null
        val sense = if (turned >= 0) 1 else -1
        place = PdrPlace(place.buildingId, to)
        c.exitGate = null
        c.exitAhead = false
        c.part = ClimbPart.SECOND
        c.changes++
        c.changedInLeg = true
        c.secondSteps = 0
        c.progress = 0.0
        c.flightHeading = null
        c.straight = 0
        selectVariant(c, if (c.up) sense else -sense)
        climb = c
        watch = StairWalk()
        lastStairChange = StairChange(c.stair.key, c.stair.buildingId, from, to, sense, guessed = false)
        stepsSinceStairChange = 0
        showClimb(c)
        return PlaceReason.STEPENICE
    }

    /**
     * Na vratima prolaza sa međupodesta, pre ulaska u prolaz: okret nazad je okret na podestu, kao povratak iz prolaza
     * ([resumeOnLanding]). Teren 13.10.2026, AMF S1 sa -1: srednjim krakom na podest, 1-2 koraka ka tremu, okret za ~180°
     * i bočnim krakom u prizemlje - tačka je stajala na vratima trema, pa je map-matching vratio niz srednji krak na -1 i
     * hodnikom do glavnog ulaza (lažan izlazak napolje). Posle [LANDING_GATE_STEPS] koraka bez okreta se ne prati.
     * Teren 27.10.2026 (isti put): dva koraka ka tremu, pa okret ulevo postepeno - korak izlaska je već bio −41° od kraka, a okret
     * se merio od njega, pa za 8 koraka nije stigao do [TURN_DONE_DEG] (−102°) i tačka je ostala na vratima trema ("neuspeli
     * prelazak sprata"). Sada se okret meri od smera kraka ([Climb.gateTurned] počinje okretom koraka izlaska).
     */
    private fun turnAtGate(c: Climb, azimuthDeg: Float): PlaceReason? {
        c.lastHeading?.let { c.gateTurned += angleDiffDeg(azimuthDeg, it) }
        c.lastHeading = azimuthDeg
        if (abs(c.gateTurned) >= TURN_DONE_DEG) {
            landingClimb = null
            return resumeOnLanding(c, c.gateTurned)
        }
        if (++c.gateSteps > LANDING_GATE_STEPS) landingClimb = null
        return null
    }

    /**
     * Na vratima prolaza sa međupodesta ([landingClimb]), korak unapred (pravcem vrata, do [GATE_AHEAD_DEG]): tačka ostaje na
     * vratima, a slobodna pozicija ide napred - prolaz počinje ([leaveThroughGateway]). Inače bi korak iskošen ka podestu tačku
     * prebacio na ivicu podesta pored vrata, gde ostaje (AMF S1 -> trem ka Kuli; replay 03.10.2026 posle izmena 16.10.).
     */
    private fun gateStep(c: Climb, azimuthDeg: Float, lengthM: Float): Boolean {
        val (inner, gate) = c.exitGate ?: return false
        val match = match?.takeIf { it.point.to.id == gate.id || it.point.from.id == gate.id } ?: return false
        val a = local(inner.x, inner.y)
        val b = local(gate.x, gate.y)
        if (abs(angleDiffDeg(headingOnPlan(azimuthDeg), azimuthOf(b.x - a.x, b.y - a.y))) > GATE_AHEAD_DEG) return false
        val scale = graph.placement(place.buildingId).scale
        val (dxM, dyM) = stepVector(place, azimuthDeg, lengthM)
        val moved = match.copy(x = (match.x + dxM / scale.widthM).toFloat(), y = (match.y + dyM / scale.heightM).toFloat())
        this.match = moved
        raw = Offset(moved.x, moved.y)
        walkedX += dxM
        walkedY += dyM
        return true
    }

    /** Korak u prolazu: računa se deo koraka u pravcu izlaza (nazad umanjuje pređeno). */
    private fun passageStep(passage: Passage, azimuthDeg: Float, lengthM: Float): PlaceReason? {
        val (dx, dy) = stepVector(PdrPlace.CAMPUS, azimuthDeg, lengthM)
        passage.doneM += dx * passage.outward.x + dy * passage.outward.y
        passage.lastHeading?.let { passage.turned += angleDiffDeg(azimuthDeg, it) }
        passage.lastHeading = azimuthDeg
        return updatePassage(passage)
    }

    private fun updatePassage(passage: Passage): PlaceReason? {
        val before = place
        when {
            passage.doneM >= passage.lengthM -> {
                moveTo(passage.target)
                if (passage.target.type == NodeType.ULAZ && !passage.target.isIndoor) exitedEntrance = passage.target
                return PlaceReason.PROLAZ
            }
            // Vratio se u hodnik.
            passage.doneM < -PASS_BEYOND_M -> {
                setAnchor(passage.from, relative(passage.from, passage.start))
                stepsSinceChange = 0
                passage.resume?.let { return resumeOnLanding(it, passage.turned) }
                return PlaceReason.PROLAZ.takeIf { place != before }
            }
        }
        // Do pola puta je pozicija u zgradi iz koje se izlazi, posle u onoj u koju se ulazi.
        val shown = if (passage.doneM < passage.lengthM / 2) passage.from else passage.target.place
        val t = (passage.doneM / passage.lengthM).coerceIn(0.0, 1.0)
        place = shown
        raw = relative(shown, PointM(passage.start.x + (passage.end.x - passage.start.x) * t, passage.start.y + (passage.end.y - passage.start.y) * t))
        match = null
        return PlaceReason.PROLAZ.takeIf { place != before }
    }

    /** GPS je potvrdio izlazak: na ulazu ako je PDR bio blizu njega, inače na GPS lokaciji. */
    private fun exitByGps(fix: GpsFix) {
        val here = meters(place, match?.let { Offset(it.shownX, it.shownY) } ?: raw ?: return, local = true)
        val gate = gateways[place].orEmpty()
            .filter { it.target.type == NodeType.ULAZ && !it.target.isIndoor }
            .minByOrNull { distance(meters(place, Offset(it.node.x, it.node.y), local = true), here) }
            ?.takeIf { distance(meters(place, Offset(it.node.x, it.node.y), local = true), here) <= GPS_EXIT_GATE_M }
        if (gate != null) {
            moveTo(gate.target)
            exitedEntrance = gate.target
        } else {
            setAnchor(PdrPlace.CAMPUS, relative(PdrPlace.CAMPUS, fix.point))
            varianceM2 = (fix.accuracyM * fix.accuracyM).toDouble()
            stepsSinceChange = 0
        }
    }

    private fun buildGateways(): Map<PdrPlace, List<Gateway>> {
        val result = HashMap<PdrPlace, MutableList<Gateway>>()
        for (node in graph.nodes.filter { it.isIndoor }) {
            for ((next, type) in graph.neighbors(node.id)) {
                if (type != EdgeType.HOD || next.buildingId == node.buildingId) continue
                // Spojni prolaz kampusa (K-P-...) vodi dalje u drugu zgradu sa planom; ako je nema (ITC), na kampus.
                val beyond = if (next.isIndoor || next.type != NodeType.PROLAZ) emptyList() else {
                    graph.neighbors(next.id).map { it.first }
                        .filter { it.isIndoor && it.buildingId != node.buildingId }
                        .sortedWith(compareBy({ abs(it.floor - node.floor) }, { it.floor }))
                }
                // Čvor prolaza kampusa (težište OSM spojnog dela) nije na putu kojim se hoda - preskače se.
                result.getOrPut(node.place) { mutableListOf() } += Gateway(node, beyond.firstOrNull() ?: next, beyond.drop(1))
            }
        }
        return result
    }

    /** Metri plana mesta [place]: [local] = bez smeštaja (za sidro i korake), inače metri kampusa. */
    private fun meters(place: PdrPlace, p: Offset, local: Boolean = false): PointM {
        val placement = graph.placement(place.buildingId)
        return if (local) {
            PointM(p.x * placement.scale.widthM.toDouble(), p.y * placement.scale.heightM.toDouble())
        } else {
            placement.toMeters(p.x, p.y)
        }
    }

    private fun relative(place: PdrPlace, p: PointM): Offset =
        graph.placement(place.buildingId).toRelative(p).let { (x, y) -> Offset(x, y) }

    private val Node.isIndoor: Boolean get() = indoorBuilding(buildingId) != null

    private val Node.place: PdrPlace get() = if (isIndoor) PdrPlace(buildingId, floor) else PdrPlace.CAMPUS
}

private fun distance(a: PointM, b: PointM) = hypot(a.x - b.x, a.y - b.y)

/**
 * Pravo stepenište (AMF S2: dva kraka u nizu): drugi krak ide istim smerom kao prvi. Kod stepeništa sa okretom su suprotni.
 */
private fun isStraight(path: List<PlanPoint>): Boolean {
    val (a, b, c, d) = path
    return (b.x - a.x) * (d.x - c.x) + (b.y - a.y) * (d.y - c.y) > 0
}

/** Strana okreta pri penjanju putanjom [path] (+1 u smeru kazaljke; 0 za pravo stepenište). */
private fun upTurnOf(path: List<PlanPoint>): Int {
    if (isStraight(path)) return 0
    val (a, b, c) = path
    // Ugao u relativnim koordinatama (x udesno, y naniže) ima isti znak kao u metrima.
    return if ((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > 0) 1 else -1
}

/** Tačka na izlomljenoj liniji [points] na delu [t] (0..1) njene dužine (u relativnim koordinatama plana [scale]). */
private fun pointAlong(points: List<PlanPoint>, t: Double, widthM: Double, heightM: Double): Offset {
    val lengths = points.zipWithNext { p, q -> hypot((q.x - p.x) * widthM, (q.y - p.y) * heightM) }
    var rest = lengths.sum() * t.coerceIn(0.0, 1.0)
    for ((i, length) in lengths.withIndex()) {
        if (rest <= length || i == lengths.lastIndex) {
            val k = if (length > 0) (rest / length).coerceIn(0.0, 1.0).toFloat() else 0f
            val (p, q) = points[i] to points[i + 1]
            return Offset(p.x + (q.x - p.x) * k, p.y + (q.y - p.y) * k)
        }
        rest -= length
    }
    return Offset(points.last().x, points.last().y)
}

/** Koliko je [p] od [origin] u pravcu [from] -> [to] (m); 0 ako pravca nema. */
private fun along(p: PointM, origin: PointM, from: PointM, to: PointM): Double {
    val length = distance(from, to).takeIf { it > 0 } ?: return 0.0
    return ((p.x - origin.x) * (to.x - from.x) + (p.y - origin.y) * (to.y - from.y)) / length
}

/** Azimut vektora u metrima plana (x udesno, y naniže): 0 = gore, u smeru kazaljke. */
private fun azimuthOf(dx: Double, dy: Double): Float = Math.toDegrees(atan2(dx, -dy)).toFloat()
