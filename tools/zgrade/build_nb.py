"""
Nastavni blok (NB): šematski crteži svih 7 nivoa (-1 ... V sprat) i unutrašnji graf.

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/zgrade/build_nb.py [--check <folder sa w-1..w5.png>]

Pravi:
  - app/src/main/res/drawable/floor_plan_nb_{m1,0,1,2,3,4,5}.xml,
  - app/src/main/assets/nb.json (čvorovi, ivice, veze sa grafom kampusa; isti format kao ntp.json),
  - uz --check: <folder>/check{-1..5}.png - crtež i graf preko ispravljenih snimaka (prepare_screens.py nb).

Izvor: snimci ekrana veb aplikacije FtnGO (v0.8.4; images/nb-1.png ... nb5.png, van gita), ispravljeni
skriptom prepare_screens.py u koordinate ovog plana. Brojevi i nazivi sala su sa tih snimaka. Pre toga
je plan prizemlja bio precrtan sa evakuacionog plana (floor_plan_placeholder.xml) - raspored hodnika,
stepeništa, portirnice i ulaza se poklapa.

Koordinate: zajednički sistem (common.py); spoljni zid WALL je pravougaonik OSM obrisa (63,4 x 21,1 m).
Orijentacija: desno = sever, dole = istok (glavni ulaz), levo = jug (prolaz ka Kuli), gore = zapad
(Amfiteatri).

Pojednostavljeno: vrata sale su na sredini zida prema hodniku (FtnGO vrata nisu precrtavana), čvor
hodnika je naspram vrata. V sprat je srednji pojas zgrade (potkrovlje); do njega vodi glavno stepenište
i spiralno stepenište iz hodnika IV sprata. Lift ide od -1 do IV sprata (potvrđeno na terenu 01.10.2026).

Teren 01.10.2026 (korisnik + fotografija evakuacionog plana I sprata):
  - spojni prolaz ka Kuli postoji i na I spratu (indoor_links: NB-1-PROLAZ <-> KULA-1-PROLAZ-NB),
  - na I-IV spratu pored stepeništa su toaleti (FtnGO: 113/110, 212/209, 316/313, 412/410 - nisu u
    rasporedu), malo uvučeni: između hodnika i stepeništa/toaleta je hol,
  - AH6 i AH7 su podeljeni na pola: AH6A i AH7A su polovine bliže stepeništu,
  - 012 ("O12" u rasporedu) je u prizemlju blizu 013/014 - prvo (01.10.) stavljeno kao desni deo bloka 015;
    teren 02.10.2026: "012 je između 016 i 014" - FtnGO tu nema sobu, pa je prostor 014-016 podeljen na tri
    (širine su PRETPOSTAVKA), a 015 je opet cele širine kao na FtnGO-u.
"""

import argparse
from pathlib import Path

from common import STEPS, Flights, R, build_graph, check_images, rect_poly, row, write_all

VX, VY, VW, VH = VIEWPORT = (305, 305, 750, 275)  # viewport plana (px plana)
WALL = (413, 311, 1038, 519)  # spoljni zid: 625 x 208 px = OSM obris 63,4 x 21,1 m
BUILDING = "NB"
FLOORS = [-1, 0, 1, 2, 3, 4, 5]
TOP_SLAB = (413, 368, 1038, 455)  # V sprat (prepare_screens.nb_top_slab, zaokruženo)

STAIRS = [("S", (624, 318, 642, 377), (633, 382))]  # krak glavnog stepeništa (levo od lifta)
LIFTS = [("L", (643, 330, 664, 372), (653, 382))]
# Glavno stepenište obilazi lift (teren 05.10.2026, crtež korisnika na snimku ekrana + virtuelna tura "nb stepenice.png"):
# sa podesta sprata levo od lifta krak naviše, iza lifta (uz zapadni zid) međupodest, desno od lifta krak naniže.
# Penje se levim krakom, okret udesno iza lifta, pa desnim krakom ka sledećem spratu - stiže se desno od lifta.
# Na najnižem nivou (-1) krak naniže ne postoji (ne crta se, nema čvora S-D); V sprat ima drugi crtež (bez krakova).
FLIGHTS = Flights(up=(624, 330, 643, 377), down=(664, 330, 691, 377), landing=(624, 311, 691, 330),
                  path=[(633, 382), (633, 320), (677, 320), (677, 382)])
FLIGHTS_LOWEST = {**FLIGHTS, "draw_down": False}
SPIRAL = ("S2", (908, 424, 928, 440), (918, 432))  # spiralno stepenište IV -> V sprat

# Posebne tačke prizemlja; veze sa kampusom (build_campus.py pravi te čvorove).
ENTRANCE = (666, 522)
PASSAGE_KULA = (322, 422)
PASSAGE_AMF = (680, 313)
# Prolaz ka Amfiteatrima je sa međupodesta glavnog stepeništa (FtnGO: "stepenicama naviše"; plan: otvor iznad desnog
# kraja međupodesta). Do 05.10.2026 je bio grana pravo iz hodnika - kroz desni krak (silazak u suteren).
AMF_VIA = ("S", [("PODEST", (633, 320), STEPS), ("PODEST-AMF", (680, 320))])
CAMPUS_LINKS = [("K-U-NB-1", "NB-0-ULAZ"), ("K-P-NB-KULA", "NB-0-PROLAZ"), ("K-P-AMF-NB", "NB-0-PROLAZ-AMF")]
# Veze sa drugom zgradom mimo kampusa (zastakljen prolaz NB - Kula na I spratu, evakuacioni plan).
INDOOR_LINKS = [("NB-1-PROLAZ", "KULA-1-PROLAZ-NB")]


def toilets():
    """Toaleti desno od stepeništa (I-IV sprat), uvučeni: između njih i hodnika je hol."""
    return [R(691, 727, 311, 377), R(727, 760, 311, 377)]


def hall(y1):
    """Hol između hodnika i stepeništa/toaleta."""
    return rect_poly(624, 377, 760, y1)


def spine(y, x0, x1):
    return [[(x0, y), (x1, y)]]


def floor_m1():
    return {
        "title": "Sprat -1 (suteren)",
        "paths": spine(431, 425, 955),
        "corridors": [[(416, 413), (624, 413), (624, 377), (691, 377), (691, 387), (760, 387), (760, 311),
                       (965, 311), (965, 519), (760, 519), (760, 450), (416, 450)]],
        "rooms": [
            *row(311, 413, [(416, 487, None), (487, 555, "P03")]),
            R(555, 625, 311, 369), R(555, 625, 369, 413, "P01"),
            *row(450, 519, [(416, 487, None), (487, 555, None), (555, 625, "P08"), (625, 665, None),
                            (665, 692, None), (692, 725, None), (725, 760, None)]),
            *row(311, 387, [(692, 727, "P21"), (727, 760, "P20")]),
            R(967, 1002, 311, 353, "P18", door=(967, 332)), R(967, 1002, 353, 412, "P17", door=(967, 382)),
            R(1002, 1038, 311, 412), R(965, 1038, 452, 519),
        ],
        "columns": [(830, 412), (898, 412), (830, 452), (898, 452)],
        "stairs": STAIRS,
        "lifts": LIFTS,
        "flights": {"S": FLIGHTS_LOWEST},
    }


def floor_0():
    return {
        "title": "Prizemlje",
        "paths": spine(420, 425, 1015) + [[(666, 420), (666, 480)]],  # + predvorje ka glavnom ulazu
        "corridors": [
            [(416, 383), (692, 383), (692, 378), (760, 378), (760, 405), (1038, 405), (1038, 456), (688, 456),
             (688, 519), (645, 519), (645, 456), (416, 456)],
            rect_poly(305, 403, 416, 442),  # spojni prolaz ka Kuli
            rect_poly(668, 305, 692, 311),  # prolaz ka Amfiteatrima (stepenicama naviše)
        ],
        "rooms": [
            *row(311, 383, [(416, 487, "018"), (487, 520, "019"), (520, 554, "021-3"), (554, 589, "021-2"),
                            (589, 624, "021-1")]),
            R(416, 452, 373, 407, "018A"),
            *row(311, 378, [(692, 727, None), (727, 760, "008")]),
            R(760, 1038, 311, 405, "Svečana sala"),
            *row(456, 519, [(416, 487, "015"), (487, 520, "013"), (520, 543, "014"), (543, 566, "012"), (566, 589, "016"),
                            (589, 618, "017"), (618, 645, "Portir"), (688, 760, None),
                            (760, 1003, "Studentska služba"), (1003, 1038, "002")]),
        ],
        "columns": [(452, 403), (487, 403), (554, 403), (622, 403), (487, 442), (554, 442), (622, 442)],
        "entrances": [(645, 519, 688, 527), (1034, 408, 1042, 452)],
        "steps": [(638, 527, 695, 545)],
        "stairs": STAIRS,
        "lifts": LIFTS,
        "flights": {"S": FLIGHTS},
        "points": [("ULAZ", ENTRANCE, "ULAZ"), ("PROLAZ", PASSAGE_KULA, "PROLAZ"), ("PROLAZ-AMF", PASSAGE_AMF, "PROLAZ", AMF_VIA)],
    }


def floor_1():
    return {
        "title": "I sprat",
        "paths": spine(423, 425, 1025),
        "corridors": [rect_poly(416, 406, 1038, 441), hall(406),
                      rect_poly(305, 404, 416, 440)],  # zastakljen prolaz ka Kuli
        "rooms": [
            *row(311, 406, [(416, 449, None), (449, 485, "102"), (485, 624, "101"),
                            (760, 932, "109A"), (932, 1038, "109")]),
            *toilets(),
            *row(441, 519, [(416, 520, "103"), (520, 588, "104"), (588, 691, "105"), (691, 759, "106"),
                            (759, 830, "107"), (830, 932, "108"), (932, 1038, "108A")]),
        ],
        "stairs": STAIRS,
        "lifts": LIFTS,
        "flights": {"S": FLIGHTS},
        "points": [("PROLAZ", PASSAGE_KULA, "PROLAZ")],
    }


def floor_2():
    return {
        "title": "II sprat",
        "paths": spine(415, 425, 1025),
        "corridors": [rect_poly(416, 395, 1038, 436), hall(395)],
        "rooms": [
            # 204/204A, 205/205A i 208/208A su po jedna velika učionica sa dva ulaza (korisnik; na FtnGO-u
            # su dve oznake, 204A čak sa zidom - verovatno ranije odvojene). "…A" su drugi ulazi (ROOM_ALIASES).
            *row(311, 395, [(416, 521, "202"), (521, 624, "201"), (933, 1038, "207")]),
            *toilets(),
            R(760, 933, 311, 395, "208", doors=[(803, 395), (890, 395)]),
            *row(436, 519, [(416, 588, "203"), (934, 1038, "206")]),
            R(588, 760, 436, 519, "204", doors=[(640, 436), (726, 436)]),
            R(760, 934, 436, 519, "205", doors=[(803, 436), (890, 436)]),
        ],
        "stairs": STAIRS,
        "lifts": LIFTS,
        "flights": {"S": FLIGHTS},
    }


def floor_3():
    return {
        "title": "III sprat",
        "paths": spine(415, 425, 1025),
        "corridors": [rect_poly(416, 395, 1038, 436), hall(395)],
        "rooms": [
            # Računarski centar: FtnGO "L1 (301)", u rasporedu "L1 (RC)".
            *row(311, 395, [(416, 468, "L2 (RC)"), (468, 521, "L4 (RC)"), (521, 624, "L1 (RC)"),
                            (760, 830, "312"), (830, 933, "311"), (933, 1038, "310")]),
            *toilets(),
            *row(436, 519, [(416, 484, "L3 (RC)"), (484, 521, "303A"), (521, 587, "L5 (RC)"),
                            (587, 639, "L6 (RC)"), (639, 691, "306A"), (691, 759, "306"), (759, 829, "307"),
                            (829, 932, "308"), (932, 1038, "309")]),
        ],
        "stairs": STAIRS,
        "lifts": LIFTS,
        "flights": {"S": FLIGHTS},
    }


def floor_4():
    return {
        "title": "IV sprat",
        "paths": spine(414, 405, 1025),
        "corridors": [[(416, 395), (1038, 395), (1038, 434), (416, 434), (416, 431), (400, 427), (396, 415),
                       (400, 403), (416, 399)],  # zaobljen južni kraj hodnika
                      hall(395)],
        "rooms": [
            # AH6 i AH7 su podeljeni na pola; "A" je polovina bliža stepeništu (teren 01.10.2026).
            *row(311, 395, [(416, 521, "AH1A"), (521, 554, None), (554, 624, "AH8"), (761, 830, "AH7A"),
                            (830, 899, "AH7"), (899, 968, "AH6A"), (968, 1038, "AH6")]),
            *toilets(),
            *row(434, 519, [(416, 554, "AH1B"), (554, 659, "AH2"), (659, 762, "AH3"), (762, 831, "AH4"),
                            (831, 900, "AH4A"), (900, 934, "406"), (934, 1038, "AH5")]),
        ],
        "stairs": STAIRS + [SPIRAL],
        "lifts": LIFTS,
        "flights": {"S": FLIGHTS},
    }


def floor_5():
    x0, y0, x1, y1 = TOP_SLAB
    return {
        "title": "V sprat (potkrovlje)",
        "roof": rect_poly(*WALL),
        "outline": [rect_poly(*TOP_SLAB)],
        "paths": spine(402, 528, 961),
        "corridors": [rect_poly(520, 394, 969, 411), rect_poly(624, y0, 691, 394)],
        "rooms": [
            R(x0, 520, y0, y1, "AH-CRT", door=(520, 402)),  # FtnGO "ARH CRT (502)"
            R(969, x1, y0, y1, "AH9", door=(969, 402)),  # FtnGO "AH9 (517)"
            *row(y0, 394, [(520, 554, "501A"), (554, 624, "501"), (691, 726, "500"), (726, 763, "509A"),
                           (763, 797, None), (797, 830, "512")]),
            *row(y0, 400, [(830, 900, "520"), (900, 934, "519"), (934, 969, "518")]),
            *row(411, y1, [(520, 554, "503"), (554, 589, "504"), (589, 623, None), (623, 658, "505"),
                           (658, 691, "506"), (691, 762, "509"), (762, 797, "510"), (797, 831, "511")]),
            R(831, 901, 416, y1, "521"),
            R(934, 951, 415, 435, "515"), R(951, 969, 415, 435, "516"),
        ],
        "stairs": [("S", (624, y0, 645, 394), (635, 398)), SPIRAL],
    }


PLANS = {-1: floor_m1(), 0: floor_0(), 1: floor_1(), 2: floor_2(), 3: floor_3(), 4: floor_4(), 5: floor_5()}


def build():
    return build_graph(BUILDING, PLANS, stairs={"S": FLOORS, "S2": [4, 5]}, lifts={"L": FLOORS[:-1]})


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", type=Path, help="folder sa w-1..w5.png (prepare_screens.py nb)")
    args = parser.parse_args()
    g = build()
    write_all(g, PLANS, VIEWPORT, WALL, "Nastavni blok", "build_nb.py", "floor_plan_nb", "NB-0-ULAZ", CAMPUS_LINKS,
              INDOOR_LINKS)
    if args.check:
        check_images(g, PLANS, VIEWPORT, WALL, args.check)


if __name__ == "__main__":
    main()
