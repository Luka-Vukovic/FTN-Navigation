"""
Kula (u OSM-u "Rektorat - Kula"): crteži nivoa 0 ... 9 i unutrašnji graf.

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/zgrade/build_kula.py [--check <folder sa w0..w9.png>]

Pravi res/drawable/floor_plan_kula_{0..9}.xml i assets/kula.json (isti format kao nb.json).

Izvor: snimci FtnGO-a (images/kula0.png ... kula9.png; kula-1.png je prazna prostorija sa stepeništem i ne
crta se) i fotografija iz virtuelne ture ("kula spaja nb i amf.png": hol u prizemlju sa vratima na trem ka
Amfiteatrima i prolazom ka NB-u).

Uglavnom kancelarije; oznake (101, 201...) se ponavljaju i u Nastavnom bloku, pa su sale ovde "Kula 101".
Jezgro: lift u sredini, stepenište desno od njega, toaleti ispod stepeništa; hodnik oko lifta. IV ... IX
sprat imaju isti raspored (5 soba gore, 2 levo, 5 dole, 1 desno). Prizemlje: glavni ulaz dole, trem ka
Amfiteatrima gore, prolaz ka NB-u desno.
"""

import argparse
from pathlib import Path

from common import TOALET, R, build_graph, check_images, col, rect_poly, row, write_all

VX, VY, VW, VH = VIEWPORT = (140, 300, 280, 275)
WALL = (155, 375, 335, 554)  # OSM obris u zajedničkom sistemu (18,3 x 18,2 m)
BUILDING = "KULA"
FLOORS = list(range(10))

# Spiralno stepenište (korisnik, teren 18. i 19.10.2026): kvadratna spirala oko okna, naviše u smeru kazaljke, krakovi od 3, 7, 3 i
# 7 gazišta; prvi krak (3) je paralelan pravcu Kula -> Amfiteatri (gore na planu), duži krakovi duž dužeg zida stepeništa.
# Poslednje gazište svakog kraka je veće, kvadratno (ugaoni podest). Krug počinje i završava se u donjem levom uglu - tu je sprat;
# čvor stepeništa je levo od njega, uz hodnik oko lifta. Putanja ide u JSON ("spirals") - po njoj se tačka kreće (PdrLocator).
SPIRAL = {"loop": [(290.5, 454.5), (290.5, 432.5), (327.5, 432.5), (327.5, 454.5), (290.5, 454.5)],
          "treads": [3, 7, 3, 7], "width": 11}
STAIRS = [("S", (285, 428, 333, 461), (280, 455))]
CAMPUS_LINKS = [("K-U-KULA-1", "KULA-0-ULAZ"), ("K-P-NB-KULA", "KULA-0-PROLAZ-NB"), ("K-P-AMF-KULA", "KULA-0-PROLAZ-AMF")]

# Evakuacioni planovi P, II, III i IV (teren 08.11.2026, teren/izveštaj36/IMG_20261108_1032*-1035*.jpg; perspektiva ispravljena po 4
# spoljna ugla zgrade na zid 155..335 x 375..554). Korisnik: "preciznije prati evakuacione planove, osim kad baš kažem da nešto nije
# tačno", "neka ti to bude primer i za spratove koje nisam slikao" (I, V-IX). Do tada je sve bilo sa snimaka FtnGO-a.
# Isto na svim slikanim spratovima: lift sa dve kabine u sredini (x 229-263, vrata ka donjem hodniku), stepenište desno od njega
# (spirala, korisnik 18./19.10.), blok toaleta ispod stepeništa (y 461-475 - FtnGO je tu imao "018" u P i "110" na I), gornji red soba
# y 375-428, levo dve sobe (428-465, 465-501), donji red y 501-554, desno od donjeg hodnika mala soba (n13; II 213, III 307, IV 413 -
# FtnGO je IV-IX zvao n15), hodnik gore y 428-452 i dole y 475-501, hol između lifta i stepeništa x 263-285. Krajnje sobe uz levi zid
# (gore n05, dole n08) i sobe uz desni zid (gore n01, dole n12) nemaju vrata na hodnik - ulazi se kroz susednu sobu (evakuacione
# strelice vode kroz nju): putanja kroz susednu sobu do njihovih vrata.
LIFT = ("L", (229, 452, 263, 475), (246, 480))
LIFT_0 = ("L", (229, 457, 263, 482), (246, 486))
WEST_BLOCK = R(208, 228, 452, 475)  # zapadno od lifta (II, IV): soba bez naziva, nema prolaza oko lifta
TOP_Y, MID_Y, BOTTOM_Y = 428, 465, 501
TOP_PATH_Y, BOTTOM_PATH_Y = 440, 488
X5 = [155, 192, 227, 263, 298, 335]  # pet soba u redu (IV: 405-401 gore, 408-412 dole)
FIVE_DOORS = [175, 220, 245, 280, None]  # vrata na hodnik/susednu sobu po planu IV; peta soba kroz četvrtu (SUITE_DOOR)
SUITE_TOP = [(280, TOP_PATH_Y), (280, 416), (296, 416)]  # kroz n02 do vrata n01
SUITE_BOTTOM = [(280, BOTTOM_PATH_Y), (280, 512), (296, 512)]  # kroz n11 do vrata n12


def k(n):
    return f"Kula {n}"


def toilets():
    """
    Blok toaleta ispod stepeništa (na planovima 3-4 kabine sa vratima ka pojasu y 475-482): jedan čvor sa vratima na hol (x 285). Putanja
    tim pojasom je probana 08.11.2026 i napuštena: pri silasku spiralom (replay 23.10., Kula I) tačka se zalepila za nju i ulaz na
    spiralu nije prepoznat (cela dalja putanja na pogrešnom mestu).
    """
    return [R(285, 333, 461, 475, amenity=TOALET, door=(285, 468))]


def five(y0, y1, numbers, wall, suite_y):
    """Red od pet soba (X5) sa vratima po planu IV; peta (uz desni zid) kroz četvrtu - vrata na zidu između njih (suite_y)."""
    rooms = []
    for (a, b), n, x in zip(zip(X5, X5[1:]), numbers, FIVE_DOORS):
        door = (x, wall) if x is not None else (298, suite_y)
        rooms.append(R(a, b, y0, y1, k(n) if n else None, door=door))
    return rooms


def corridors(right_room_x0):
    """Hodnik gore, hol uz stepenište, hodnik dole (do male sobe desno) i pojas ka evakuacionom izlazu (ne koristi se)."""
    return [rect_poly(208, TOP_Y, 263, 452), rect_poly(263, TOP_Y, 285, 475), rect_poly(208, 475, right_room_x0, BOTTOM_Y),
            rect_poly(285, 475, 335, 482)]


def typical(f):
    """IV ... IX sprat (po planu IV)."""
    n = f * 100
    return {
        "title": f"{f}. sprat",
        # Prsten oko lifta nije zatvoren: zapadno od lifta je soba (IV: sa vratima na donji hodnik). Gornji hodnik od x 175 i donji od
        # x 175 prolaze kroz n06/n07 do vrata n05/n08.
        # Redosled je bitan: kraj linije se spaja samo sa linijom navedenom pre nje (common.Network.finish).
        "paths": [[(175, TOP_PATH_Y), (280, TOP_PATH_Y)], [(175, BOTTOM_PATH_Y), (283, BOTTOM_PATH_Y)],
                  [(274, TOP_PATH_Y), (274, BOTTOM_PATH_Y)], SUITE_TOP, SUITE_BOTTOM],
        "corridors": corridors(285),
        "rooms": [
            *five(375, TOP_Y, [n + 5, n + 4, n + 3, n + 2, n + 1], TOP_Y, 416),
            R(155, 208, TOP_Y, MID_Y, k(n + 6), door=(208, TOP_PATH_Y)),
            R(155, 208, MID_Y, BOTTOM_Y, k(n + 7), door=(208, 489)),
            *five(BOTTOM_Y, 554, [n + 8, n + 9, n + 10, n + 11, n + 12], BOTTOM_Y, 512),
            R(285, 335, 482, BOTTOM_Y, k(n + 13), door=(285, BOTTOM_PATH_Y)),
            WEST_BLOCK,
            *toilets(),
        ],
        "stairs": STAIRS,
        "lifts": [LIFT],
    }


def floor_0():
    """
    Prizemlje po evakuacionom planu (08.11.2026). Gore hol od trema ka Amfiteatrima (vrata x 233-256) do prolaza ka NB-u (desno, y
    406-428) - jedna prostorija (korisnik: "nemoj da te zbune različite boje u prolazu između NB i AMF kroz kulu ... nema nikakvih zidova").
    Iz hola dvokrilna vrata (x 264-284) u centralni hol oko lifta; zapadno od lifta je zid - centralni hol nije prsten. Levo: 001A sa
    vratima u predvorje (x 209-227, y 418-432), uska soba iznad predvorja, mali hodnik (y 432-447) sa vratima u centralni hol (x 228,
    y 441), soba bez broja; ispod njih 003 sa vratima u drugo predvorje (y 489-509, vrata u centralni hol x 228, y 496), a iz njega
    vrata 004 (dole). Izlazni hodnik x 229-262 ka glavnom ulazu. Desno: 005 (dole) i 006 (vrata sa leve strane), toaleti ispod
    stepeništa (FtnGO: "018").
    """
    return {
        "title": "Prizemlje",
        # Čvorovi trema i ulaza su tačke (points) - putanje se završavaju pre njih, kao do sada (inače dupli čvor na istom mestu).
        # Redosled je bitan: kraj linije se spaja samo sa linijom navedenom pre nje (common.Network.finish).
        "paths": [[(245, 413), (320, 413)],  # hol: trem -> prolaz ka NB-u (pravo preko hola, korisnik 16.10.)
                  [(218, 495), (290, 495), (290, 502)],  # predvorje 003/004 -> ispod lifta -> 006
                  [(274, 413), (274, 495)],  # kroz dvokrilna vrata, istočno od lifta
                  [(218, 424), (218, 444), (274, 444)],  # predvorje 001A, mali hodnik -> centralni hol
                  [(245, 495), (245, 530)]],  # izlazni hodnik (do ulaza 554)
        "corridors": [rect_poly(228, 375, 333, 431),  # hol od trema do prolaza ka NB-u
                      rect_poly(228, 431, 285, 457), rect_poly(263, 457, 285, 482), rect_poly(228, 482, 296, 509),
                      rect_poly(209, 418, 228, 447), rect_poly(209, 489, 228, 509),  # predvorja levo
                      rect_poly(229, 509, 262, 554),  # izlazni hodnik
                      rect_poly(205, 300, 280, 375),  # trem ka Amfiteatrima
                      rect_poly(335, 397, 420, 430)],  # prolaz ka NB-u
        "rooms": [
            R(155, 209, 375, 431, k("001A"), door=(209, 424)), R(209, 228, 375, 418),
            R(155, 208, 431, 471), R(155, 208, 471, 509, k("003"), door=(208, 496)),
            R(208, 228, 447, 489),
            R(155, 228, 509, 554, k("004"), door=(219, 509)), R(262, 335, 509, 554, k("005"), door=(280, 509)),
            R(296, 335, 480, 509, k("006"), door=(296, 502)),
            R(285, 326, 466, 480, amenity=TOALET, door=(290, 480)),
        ],
        "entrances": [(229, 554, 262, 560)],
        "stairs": STAIRS,
        "lifts": [LIFT_0],
        "points": [("ULAZ", (245, 554), "ULAZ"), ("PROLAZ-AMF", (245, 376), "PROLAZ"),
                   ("PROLAZ-NB", (333, 413), "PROLAZ")],
    }


def floor_1():
    """I sprat nije slikan: gornji red, 5MX i prolaz ka NB-u su sa FtnGO-a; jezgro, toaleti, levo/dole i mala soba desno po obrascu."""
    return {
        "title": "1. sprat",
        # Ka prolazu u NB pravim uglovima (korisnik nacrtao, teren 27.10.2026): od prstena pravo do visine prolaza, pa pravo do
        # prolaza - do tada koso od ugla prstena preko stepeništa, a vrata 5MX-1/5MX-2 takođe koso na ugao.
        "paths": [[(215, TOP_PATH_Y), (274, TOP_PATH_Y), (274, BOTTOM_PATH_Y), (215, BOTTOM_PATH_Y), (215, TOP_PATH_Y)],
                  [(274, BOTTOM_PATH_Y), (283, BOTTOM_PATH_Y)], [(274, TOP_PATH_Y), (274, 422), (326, 422)]],
        "corridors": [rect_poly(208, 432, 285, BOTTOM_Y), rect_poly(263, 402, 333, 461), rect_poly(285, 475, 335, 482),
                      rect_poly(335, 404, 420, 440)],  # zastakljen prolaz ka NB-u (teren 01.10.2026)
        "rooms": [
            R(155, 192, 375, 432, k(103)), R(192, 227, 375, 432, k(101), door=(215, 432)), R(227, 263, 375, 432, k(102)),
            *row(375, 402, [(263, 298, "5MX-1"), (298, 333, "5MX-2")]),
            R(155, 208, 432, MID_Y, k(104), door=(208, TOP_PATH_Y)), R(155, 208, MID_Y, BOTTOM_Y, k(105), door=(208, 489)),
            R(155, 228, BOTTOM_Y, 554, k(106), door=(219, BOTTOM_Y)), R(228, 263, BOTTOM_Y, 554, k(107), door=(245, BOTTOM_Y)),
            R(263, 335, BOTTOM_Y, 554, k(108), door=(270, BOTTOM_Y)),
            R(285, 335, 482, BOTTOM_Y, k("109A"), door=(285, BOTTOM_PATH_Y)),
            *toilets(),
        ],
        "stairs": STAIRS,
        "lifts": [LIFT],
        # Veza sa NB-1-PROLAZ je u nb.json (indoorLinks).
        "points": [("PROLAZ-NB", (333, 422), "PROLAZ")],
    }


def floor_2():
    """
    II po evakuacionom planu: gore soba (FtnGO 204/205), Dekanat, soba (FtnGO 202) - sve tri sa vratima na hodnik (i spojene vratima
    između sebe); dvokrilna vrata u gornjem hodniku (x ~262). Zapadno od lifta nema prolaza (korisnik 28.10.: "nije moguć krug oko
    lifta" - plan to potvrđuje). Dole 208-212 (208 kroz 207), mala soba 213 desno (do tada 213 na mestu 212, a 212 nije postojala).
    """
    return {
        "title": "2. sprat",
        "paths": [[(215, TOP_PATH_Y), (280, TOP_PATH_Y)], [(175, BOTTOM_PATH_Y), (304, BOTTOM_PATH_Y)],
                  [(274, TOP_PATH_Y), (274, BOTTOM_PATH_Y)]],
        "corridors": corridors(307),
        "rooms": [
            R(155, 227, 375, TOP_Y, k("204/205"), door=(219, TOP_Y)), R(227, 263, 375, TOP_Y, "Dekanat", door=(245, TOP_Y)),
            R(263, 335, 375, TOP_Y, k(202), door=(279, TOP_Y)),
            R(155, 208, TOP_Y, MID_Y, k(206), door=(208, TOP_PATH_Y)), R(155, 208, MID_Y, BOTTOM_Y, k(207), door=(208, 489)),
            R(155, 193, BOTTOM_Y, 554, k(208), door=(175, BOTTOM_Y)), R(193, 227, BOTTOM_Y, 554, k(209), door=(220, BOTTOM_Y)),
            R(227, 263, BOTTOM_Y, 554, k(210), door=(245, BOTTOM_Y)), R(263, 298, BOTTOM_Y, 554, k(211), door=(280, BOTTOM_Y)),
            R(298, 335, BOTTOM_Y, 554, k(212), door=(304, BOTTOM_Y)),
            R(307, 335, 482, BOTTOM_Y, k(213), door=(307, BOTTOM_PATH_Y)),
            WEST_BLOCK,
            *toilets(),
        ],
        "stairs": STAIRS,
        "lifts": [LIFT],
    }


def floor_3():
    """III po evakuacionom planu: Svečana sala (301) preko celog gornjeg reda, hol oko lifta je prsten, dole 304-306B (306B kroz 306A)."""
    return {
        "title": "3. sprat",
        "paths": [[(215, BOTTOM_PATH_Y), (296, BOTTOM_PATH_Y)], [(215, TOP_PATH_Y), (274, TOP_PATH_Y), (274, BOTTOM_PATH_Y)],
                  [(215, TOP_PATH_Y), (215, BOTTOM_PATH_Y)], [(271, BOTTOM_PATH_Y), (271, 512), (296, 512)]],
        "corridors": [rect_poly(208, TOP_Y, 285, 452), rect_poly(208, 452, 229, 475), rect_poly(263, 452, 285, 475),
                      rect_poly(208, 475, 299, BOTTOM_Y), rect_poly(285, 475, 335, 482)],
        "rooms": [
            R(155, 335, 375, TOP_Y, k(301), door=(246, TOP_Y)),
            R(155, 208, TOP_Y, MID_Y, k(302), door=(208, TOP_PATH_Y)), R(155, 208, MID_Y, BOTTOM_Y, k(303), door=(208, 489)),
            R(155, 227, BOTTOM_Y, 554, k(304), door=(219, BOTTOM_Y)), R(227, 263, BOTTOM_Y, 554, k(305), door=(245, BOTTOM_Y)),
            R(263, 298, BOTTOM_Y, 554, k("306A"), door=(271, BOTTOM_Y)), R(298, 335, BOTTOM_Y, 554, k("306B"), door=(298, 512)),
            R(299, 335, 482, BOTTOM_Y, k(307), door=(299, BOTTOM_PATH_Y)),
            *toilets(),
        ],
        "stairs": STAIRS,
        "lifts": [LIFT],
    }


def floor_5():
    plan = typical(5)
    # Niša iznad prstena (x 263-298) je hodnik: krak u nišu, vrata 502 i 501 sa strane niše pod pravim uglom (27.10.2026; do tada
    # koso na ugao prstena - 501 preko stepeništa). PRETPOSTAVKA - mesta vrata nisu na snimcima. Gornji red i 500 su sa FtnGO-a.
    plan["paths"] = [[(175, TOP_PATH_Y), (274, TOP_PATH_Y)], [(175, BOTTOM_PATH_Y), (283, BOTTOM_PATH_Y)],
                     [(274, TOP_PATH_Y), (274, BOTTOM_PATH_Y)], SUITE_BOTTOM, [(269, TOP_PATH_Y), (269, 385)]]
    top = five(375, TOP_Y, [505, 504, 503, None, 501], TOP_Y, 416)
    top[4]["doors"] = [(298, 391)]  # 501 iz niše
    plan["rooms"] = [
        *[r for r in top if r["rect"] != (263, 375, 298, TOP_Y)],
        R(275, 297, 407, TOP_Y, k(502), door=(275, 417)),
        R(155, 208, TOP_Y, MID_Y, k(506), door=(208, TOP_PATH_Y)), R(155, 208, MID_Y, BOTTOM_Y, k(507), door=(208, 489)),
        *five(BOTTOM_Y, 554, [508, 509, 510, 511, 512], BOTTOM_Y, 512),
        R(285, 335, 482, BOTTOM_Y, k(500), door=(285, BOTTOM_PATH_Y)),
        WEST_BLOCK,
        *toilets(),
    ]
    plan["corridors"].append(rect_poly(263, 375, 298, TOP_Y))
    return plan


PLANS = {0: floor_0(), 1: floor_1(), 2: floor_2(), 3: floor_3(), 4: typical(4), 5: floor_5(),
         **{f: typical(f) for f in range(6, 10)}}
for _plan in PLANS.values():
    _plan["spiral"] = {"S": SPIRAL}


def build():
    g = build_graph(BUILDING, PLANS, stairs={"S": FLOORS}, lifts={"L": FLOORS})
    # Svi čvorovi dostižni sa glavnog ulaza i bez stepenica (redosled linija - common.Network.finish; 08.11.2026 je pogrešan redosled
    # ostavio hodnike nepovezane).
    adj = {}
    for (a, b), kind in g.edges.items():
        if kind != "STEPENICE":
            adj.setdefault(a, []).append(b)
            adj.setdefault(b, []).append(a)
    seen, todo = {"KULA-0-ULAZ"}, ["KULA-0-ULAZ"]
    while todo:
        for m in adj.get(todo.pop(), []):
            if m not in seen:
                seen.add(m)
                todo.append(m)
    missing = sorted(set(g.nodes) - seen)
    assert not missing, missing[:10]
    return g


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", type=Path, help="folder sa w0..w9.png (prepare_screens.py kula)")
    args = parser.parse_args()
    g = build()
    write_all(g, PLANS, VIEWPORT, WALL, "Kula", "build_kula.py", "floor_plan_kula", "KULA-0-ULAZ", CAMPUS_LINKS)
    if args.check:
        check_images(g, PLANS, VIEWPORT, WALL, args.check)


if __name__ == "__main__":
    main()
