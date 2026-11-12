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

from common import STEPS, TOALET, Flights, R, build_graph, check_images, rect_poly, row, write_all

VX, VY, VW, VH = VIEWPORT = (305, 305, 750, 275)  # viewport plana (px plana)
WALL = (413, 311, 1038, 519)  # spoljni zid: 625 x 208 px = OSM obris 63,4 x 21,1 m
BUILDING = "NB"
FLOORS = [-1, 0, 1, 2, 3, 4, 5]
TOP_SLAB = (413, 368, 1038, 455)  # V sprat (prepare_screens.nb_top_slab, zaokruženo)

STAIRS = [("S", (624, 318, 647, 377), (633, 382))]  # krak glavnog stepeništa (levo od lifta)
LIFTS = [("L", (647, 330, 668, 372), (653, 382))]
# Glavno stepenište obilazi lift (teren 05.10.2026, crtež korisnika na snimku ekrana + virtuelna tura "nb stepenice.png"):
# sa podesta sprata levo od lifta krak naviše, iza lifta (uz zapadni zid) međupodest, desno od lifta krak naniže.
# Penje se levim krakom, okret udesno iza lifta, pa desnim krakom ka sledećem spratu - stiže se desno od lifta.
# Na najnižem nivou (-1) krak naniže ne postoji (ne crta se, nema čvora S-D); V sprat ima drugi crtež (bez krakova).
# Krakovi su iste širine (teren 29.10.2026, korisnik; ranije levi 19 px, desni 27 px), lift između njih - samo crtež: čvorovi
# i putanja ostaju (pomeranje za 2-3 px je u replay-u 17.10. promenilo map-matching kod dna kraka, sprat je ostao pogrešan).
FLIGHTS = Flights(up=(624, 330, 647, 377), down=(668, 330, 691, 377), landing=(624, 311, 691, 330),
                  path=[(633, 382), (633, 320), (677, 320), (677, 382)], floor_landing=True)
FLIGHTS_LOWEST = {**FLIGHTS, "draw_down": False}
SPIRAL = ("S2", (908, 424, 928, 440), (918, 432))  # spiralno stepenište IV -> V sprat

# Posebne tačke prizemlja; veze sa kampusom (build_campus.py pravi te čvorove).
# Pravo ispred spoljnih vrata predvorja, na ivici trema (evakuacioni plan; OSM ulaz je na ivici obrisa sa tremom, 0,4 m dalje). Do
# 12.11.2026 (666, 522). Na samim vratima (676, 507) prolaz do OSM ulaza je bio 1,3 m - test izlaska je video izlazak pa povratak.
ENTRANCE = (677, 517)
PASSAGE_KULA = (322, 422)
PASSAGE_AMF = (680, 313)
# Prolaz ka Amfiteatrima je sa međupodesta glavnog stepeništa (FtnGO: "stepenicama naviše"; plan: otvor iznad desnog
# kraja međupodesta). Do 05.10.2026 je bio grana pravo iz hodnika - kroz desni krak (silazak u suteren).
AMF_VIA = ("S", [("PODEST", (633, 320), STEPS), ("PODEST-AMF", (680, 320))])
# Sa I sprata se do istog međupodesta silazi desnim krakom (krak naniže), pa je prolaz pravo ispred kraja kraka. Teren
# 30.10.2026 (106 -> A2): ruta je išla dva kraka dole u prizemlje i jedan nazad gore, a na stepenicama "nije nigde bilo
# obeleženo gde tačno da idem". Ivica pravo od dna kraka naniže do prolaza (bez čvora hodnika na međupodestu - lanac preko
# čvora hodnika bi lociranje uzelo za bočni krak, kao AMF S1); prolaz sa oba nivoa vodi u isti spojni deo.
AMF_VIA_1 = ("S-D", [], STEPS)
CAMPUS_LINKS = [("K-U-NB-1", "NB-0-ULAZ"), ("K-P-NB-KULA", "NB-0-PROLAZ"), ("K-P-AMF-NB", "NB-0-PROLAZ-AMF"),
                ("K-P-AMF-NB", "NB-1-PROLAZ-AMF")]
# Veze sa drugom zgradom mimo kampusa (zastakljen prolaz NB - Kula na I spratu, evakuacioni plan).
INDOOR_LINKS = [("NB-1-PROLAZ", "KULA-1-PROLAZ-NB")]


def toilets():
    """Toaleti desno od stepeništa (I-IV sprat), uvučeni: između njih i hodnika je hol."""
    return [R(691, 727, 311, 377, amenity=TOALET), R(727, 760, 311, 377, amenity=TOALET)]


def hall(y1):
    """Hol između hodnika i stepeništa/toaleta."""
    return rect_poly(624, 377, 760, y1)


def spine(y, x0, x1):
    return [[(x0, y), (x1, y)]]


def floor_m1():
    return {
        "title": "Sprat -1 (suteren)",
        # + uz istočni zid hola do vrata P17/P18 - pravi uglovi (27.10.2026; do tada koso sa kraja hodnika, do 10 m)
        "paths": spine(431, 425, 955) + [[(955, 431), (955, 332)]],
        "corridors": [[(416, 413), (624, 413), (624, 377), (691, 377), (691, 387), (760, 387), (760, 311),
                       (965, 311), (965, 519), (760, 519), (760, 450), (416, 450)]],
        "rooms": [
            *row(311, 413, [(416, 487, None), (487, 555, "P03")]),
            R(555, 625, 311, 369), R(555, 625, 369, 413, "P01"),
            *row(450, 519, [(416, 487, None), (487, 555, None), (555, 625, "P08"), (625, 665, None),
                            (665, 692, None)]),
            # Toaleti Ž i M (ikonice na FtnGO-u).
            R(692, 725, 450, 519, amenity=TOALET), R(725, 760, 450, 519, amenity=TOALET),
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
    # Precrtano zid po zid sa evakuacionog plana prizemlja (images/IMG_20260918_120057.webp, van gita; 1 px fotografije = 1 px
    # plana - koordinatni sistem plana je nastao precrtavanjem baš te fotografije), teren 12.11.2026: "Ponovo pogledaj prizemlje
    # NB, ovo nije skroz po planu. Ne moraju sve prostorije imati oblik pravougaonika." Do tada FtnGO crtež:
    #  - istočni zid je na y 505; pojas 505..519 (i 1033..1038 na severu) je trem sa stubovima - unutra je samo pod krovom.
    #    Sobe uz istočni zid su do 505 (bile do 519), hodnik je 378..449 (bio 383..456) - hol sa dva reda stubova;
    #  - 018 je u obliku slova L (018A se uvlači u njegov ugao), 018A 368..400;
    #  - južni red (od juga): Server sala je mala prostorija u uglu hola pred prolazom ka Kuli (416..446 x 439..449, vrata ka holu sa
    #    istoka); 014 u obliku slova L (416..514 do y 470, ispod toga 416..486) BEZ vrata na hodnik - ulazi se iz 012 (vrata x 514,
    #    y ~463); iza 012 mala soba 486..522 x 470..505 (vrata ka 014, naziv nepoznat); 012 u obliku slova L (514..556, ispod y 470
    #    522..556) sa dvokrilnim vratima; 016 556..590, 017 590..627, pa blok Portirnice (627..662: mala soba i Portir, vrata ka
    #    predvorju) i predvorje glavnog ulaza 662..691 (dvoja dvokrilna vrata, y 476 i 505);
    #  - 002 je 1000..1031, Studentska služba 760..1000; Svečana sala do 1031.
    # Brojevi južnog reda: korisnik je 12.11. upisao 014, 12, 16, 17 na fotografiju plana (teren/izveštaj41/IMG_20261112_180909.jpg,
    # 3,15 px fotografije po px plana) - redosled vrata od ulaza 017, 016, 012, 014. Prva verzija istog dana je pogrešno pročitala stari
    # (mutan) plan: "SERVER SALA" kao veliku sobu sa vratima na hodnik, predsoblje kao zasebnu sobu, i 013 iza njega; FtnGO oznake 013 i
    # 015 na planu nemaju mesta.
    r018 = R(416, 486, 311, 378, "018")
    r018["poly"] = [(416, 311), (486, 311), (486, 378), (452, 378), (452, 368), (416, 368)]
    r014 = R(416, 514, 449, 505, "014", door=(514, 463))
    r014["poly"] = [(416, 449), (514, 449), (514, 470), (486, 470), (486, 505), (416, 505)]
    r012 = R(514, 556, 449, 505, "012", door=(524, 449))
    r012["poly"] = [(514, 449), (556, 449), (556, 505), (522, 505), (522, 470), (514, 470)]
    return {
        "title": "Prizemlje",
        "roof": rect_poly(*WALL),  # trem sa stubovima uz istočni (i severni) zid
        "outline": [rect_poly(413, 311, 1033, 505)],
        # + predvorje do spoljnih vrata glavnog ulaza; + kroz dvokrilna vrata 012, pa vratima u 014 do naspram vrata male sobe (pravi uglovi)
        # (linija kroz 012 i 014 je 3 px pored vrata - na samoj liniji bi ivica vrata - hodnik bila dužine 0; linija ka ulazu na x 677
        # kao dno kraka naniže, inače dva čvora hodnika 1 px jedan od drugog)
        "paths": spine(420, 425, 1021) + [[(677, 420), (677, 498)], [(527, 420), (527, 466), (496, 466)]],
        "corridors": [
            [(452, 378), (760, 378), (760, 405), (1031, 405), (1031, 449), (691, 449), (691, 505), (662, 505), (662, 449),
             (446, 449), (446, 439), (416, 439), (416, 400), (452, 400)],
            rect_poly(305, 398, 416, 432),  # spojni prolaz ka Kuli
            rect_poly(668, 305, 692, 311),  # prolaz ka Amfiteatrima (stepenicama naviše)
        ],
        "rooms": [
            r018,
            *row(311, 378, [(486, 520, "019"), (520, 554, "021-3"), (554, 589, "021-2"), (589, 625, "021-1")]),
            R(416, 452, 368, 400, "018A", door=(452, 387)),
            # Toalet: na FtnGO-u "008" sa ikonicama M i Ž; 008 nije u rasporedu (kao 113/110 na I-IV spratu).
            R(692, 727, 311, 378, door=(721, 378)), R(727, 760, 311, 378, amenity=TOALET, door=(736, 378)),
            R(760, 1031, 311, 405, "Svečana sala"),
            R(416, 446, 439, 449, door=(446, 444)),  # "SERVER SALA" na planu
            r014,
            R(486, 522, 470, 505, door=(496, 470)),  # mala soba iza 012 (vrata ka 014; naziv nepoznat)
            r012,
            R(556, 590, 449, 505, "016", door=(568, 449)),
            R(590, 627, 449, 505, "017", door=(595, 449)),
            R(627, 662, 449, 476, door=(662, 470)),
            R(627, 662, 476, 505, "Portir", door=(662, 482)),
            R(691, 760, 449, 505),
            R(760, 1000, 449, 505, "Studentska služba"),
            R(1000, 1031, 449, 505, "002"),
        ],
        "columns": [(486, 397), (556, 397), (626, 397), (486, 434), (556, 434), (626, 434), (694, 434), (763, 434), (830, 434),
                    (898, 434), (967, 434),
                    # stubovi trema
                    (420, 515), (489, 515), (558, 515), (626, 515), (694, 515), (761, 515), (830, 515), (898, 515),
                    (965, 515), (1033, 515)],
        "entrances": [(662, 503, 691, 507), (1031, 405, 1037, 432)],
        "steps": [(629, 519, 692, 548)],  # spoljne stepenice ispred trema
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
                      rect_poly(305, 404, 416, 440),  # zastakljen prolaz ka Kuli
                      rect_poly(668, 305, 692, 311)],  # prolaz ka Amfiteatrima (sa međupodesta, desnim krakom naniže)
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
        "points": [("PROLAZ", PASSAGE_KULA, "PROLAZ"), ("PROLAZ-AMF", PASSAGE_AMF, "PROLAZ", AMF_VIA_1)],
    }


def floor_2():
    return {
        "title": "II sprat",
        # Južni kraj hodnika (evakuacioni plan II sprata, teren 31.10.2026): hodnik se završava na x 481; od 542 do 481 je uzak
        # deo uz zapadni zid (vrata 202), a ispod njega sobica sa vratima ka hodniku. Do tada hodnik pune širine do 416.
        "paths": spine(415, 542, 1025) + [[(542, 415), (542, 400), (490, 400)]],
        "corridors": [[(481, 395), (1038, 395), (1038, 436), (542, 436), (542, 406), (481, 406)], hall(395)],
        "rooms": [
            # 204/204A, 205/205A i 208/208A su po jedna velika učionica sa dva ulaza (korisnik; na FtnGO-u
            # su dve oznake, 204A čak sa zidom - verovatno ranije odvojene). "…A" su drugi ulazi (ROOM_ALIASES).
            *row(311, 395, [(416, 521, "202"), (521, 624, "201"), (933, 1038, "207")]),
            *toilets(),
            R(760, 933, 311, 395, "208"),
            R(416, 481, 395, 436), R(481, 542, 406, 436),  # kraj hodnika (bez vrata na planu) i sobica
            *row(436, 519, [(416, 588, "203"), (934, 1038, "206")]),
            R(588, 760, 436, 519, "204"),
            R(760, 934, 436, 519, "205"),
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


# Vrata po FtnGO snimcima (prekidi u zidu sale prema hodniku na ispravljenim snimcima w<sprat>.png - prepare_screens.py nb;
# očitano i provereno na uvećanim isečcima, 30.10.2026). Korisnik, teren 30.10.: "pokušaj, tamo gde imaš planove, da prilagodiš
# pozicije vrata" (i 01.10.: "ostale sale imaju vrata na kraju, ne na sredini"). Široki prekidi (~1,9 m) su dvokrilna vrata
# učionica, uski (~0,9 m) obična. Sale koje ovde nema (prekid se ne vidi ili je crtež nejasan: P03, 018A, 015-017 i 012 u
# prizemlju, AH1A, 406, toaleti) ostaju sa vratima na sredini zida; P17/P18 su sa terena (27.10.).
DOORS = {
    -1: {"P01": [(610, 413)], "P08": [(568, 450)], "P21": [(713, 387)], "P20": [(740.5, 387)]},
    # Prizemlje: evakuacioni plan (12.11.2026; vrata se slažu sa FtnGO-om do ~2 px, zid hodnika je sada na y 378 i 449). Studentska
    # služba ima i druga vrata na severnom kraju.
    0: {"018": [(480, 378)], "019": [(497.5, 378)], "021-3": [(547.5, 378)], "021-2": [(581.5, 378)], "021-1": [(613, 378)],
        "Svečana sala": [(815, 405), (975, 405)], "Studentska služba": [(768, 449), (990, 449)], "002": [(1006, 449)]},
    1: {"102": [(462, 406)], "101": [(502, 406)], "109A": [(802, 406)], "109": [(982, 406)], "103": [(440, 441)],
        "104": [(538, 441)], "105": [(604, 441)], "106": [(710, 441)], "107": [(777, 441)], "108": [(854, 441)],
        "108A": [(982, 441)]},
    # 204, 205 i 208 su sale sa dva ulaza (korisnik, 30.09.) - na FtnGO-u vrata blizu oba kraja.
    # II i III sprat: provereno evakuacionim planovima (fotografije sa terena 31.10.2026, teren/izveštaj30/, ispravljene po
    # uglovima zgrade): 205 ima i treća, dvokrilna vrata na sredini; na III su vrata 312 i 310 bila pomerena (FtnGO). Ostala
    # vrata II i III se slažu sa planom (do ~1 px).
    2: {"202": [(502, 395)], "201": [(605, 395)], "208": [(783, 395), (917, 395)], "207": [(987, 395)], "203": [(568, 436)],
        "204": [(605, 436), (751, 436)], "205": [(778.5, 436), (853, 436), (913.5, 436)], "206": [(951, 436)]},
    3: {"L2 (RC)": [(432, 395)], "L4 (RC)": [(501, 395)], "L1 (RC)": [(605, 395)], "312": [(782.5, 395)], "311": [(916.5, 395)],
        "310": [(985.5, 395)], "L3 (RC)": [(432, 436)], "303A": [(505.5, 436)], "L5 (RC)": [(572, 436)],
        "L6 (RC)": [(604, 436)], "306A": [(678, 436)], "306": [(704, 436)], "307": [(782, 436)], "308": [(916, 436)],
        "309": [(950, 436)]},
    # IV: zid ispod hodnika je na snimku ~2 px niže (y 436) nego na crtežu (434) - vrata na našem zidu.
    4: {"AH8": [(604, 395)], "AH7A": [(776, 395)], "AH7": [(876, 395)], "AH6A": [(913.5, 395)], "AH6": [(1005, 395)],
        "AH1B": [(537, 434)], "AH2": [(572, 434), (642, 434)], "AH3": [(674.5, 434), (753, 434)], "AH4": [(797, 434)],
        "AH4A": [(891.5, 434)], "AH5": [(955, 434)]},
    5: {"501A": [(547.5, 394)], "501": [(611.5, 394)], "500": [(718.5, 394)], "509A": [(745, 394)], "512": [(814, 394)],
        "520": [(866, 400)], "519": [(918.5, 400)], "518": [(952, 400)], "503": [(547.5, 411)], "504": [(581, 411)],
        "505": [(650.5, 411)], "506": [(666, 411)], "509": [(733.5, 411)], "510": [(778.5, 411)], "511": [(814, 411)],
        "521": [(866.5, 416)], "515": [(941, 415)], "516": [(963, 415)]},
}


def with_doors(f, plan):
    """Vrata iz [DOORS] za sale sprata f."""
    doors = DOORS.get(f, {})
    for room in plan["rooms"]:
        if room["name"] in doors:
            room["doors"] = doors[room["name"]]
    assert set(doors) <= {r["name"] for r in plan["rooms"]}, set(doors) - {r["name"] for r in plan["rooms"]}
    return plan


PLANS = {f: with_doors(f, plan) for f, plan in
         {-1: floor_m1(), 0: floor_0(), 1: floor_1(), 2: floor_2(), 3: floor_3(), 4: floor_4(), 5: floor_5()}.items()}


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
