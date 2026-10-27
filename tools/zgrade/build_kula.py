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
STAIRS = [("S", (285, 427, 333, 460), (280, 455))]
LIFTS = [("L", (224, 447, 263, 478), (243, 484))]
RING = [[(215, 437), (274, 437), (274, 495), (215, 495), (215, 437)]]  # hodnik oko lifta
CAMPUS_LINKS = [("K-U-KULA-1", "KULA-0-ULAZ"), ("K-P-NB-KULA", "KULA-0-PROLAZ-NB"), ("K-P-AMF-KULA", "KULA-0-PROLAZ-AMF")]

X5 = [155, 192, 227, 263, 298, 333]  # pet soba u redu (gore i dole)


def k(n):
    return f"Kula {n}"


# Vrata na delu zida uz hodnik (pravi ugao do prstena, 27.10.2026; PRETPOSTAVKA - mesta vrata nisu na snimcima): druga i četvrta
# soba reda (x 192-227 i 263-298) su samo delom uz hodnik oko lifta (x 207-287) - vrata naspram prstena (x 215 i 274), ne na sredini
# zida (do tada koso na ugao prstena). Ugaone sobe i soba iznad stepeništa ne dodiruju hodnik - ostaju koso.
RING_DOOR_X = {1: 215, 3: 274}


def five(y0, y1, numbers, wall=None):
    rooms = row(y0, y1, [(a, b, k(n) if n else None) for a, b, n in zip(X5, X5[1:], numbers)])
    if wall is not None:
        for i, x in RING_DOOR_X.items():
            rooms[i]["doors"] = [(x, wall)]
    return rooms


def toilets(y0=462, y1=480):
    return [R(285, 309, y0, y1, amenity=TOALET), R(309, 333, y0, y1, amenity=TOALET)]


def typical(f):
    """IV ... IX sprat."""
    n = f * 100
    return {
        "title": f"{f}. sprat",
        "paths": RING,
        "corridors": [rect_poly(207, 427, 287, 511), rect_poly(287, 427, 333, 462)],
        "rooms": [
            *five(375, 427, [n + 5, n + 4, n + 3, n + 2, n + 1], wall=427),
            *col(155, 207, [(427, 465, k(n + 6)), (465, 511, k(n + 7))]),
            *five(511, 554, [n + 8, n + 9, n + 10, n + 11, n + 12], wall=511),
            R(287, 333, 482, 511, k(n + 15), door=(287, 496)),
            *toilets(),
        ],
        "stairs": STAIRS,
        "lifts": LIFTS,
    }


def floor_0():
    return {
        "title": "Prizemlje",
        # Levo od lifta je soba, pa hodnik nije prsten. Hol je otvoren: od prolaza iz NB-a pravo preko hola do trema ka
        # Amfiteatrima (korisnik nacrtao, teren 16.10.2026) - do tada samo koso do ugla oko lifta, pa duž hodnika do trema.
        # 27.10.2026: od hola do prstena pravo (274, 413) -> (274, 437), ne koso od (320, 413) - pravi uglovi.
        "paths": [[(210, 437), (274, 437), (274, 495), (205, 495)],
                  [(236, 437), (236, 413), (320, 413)], [(274, 413), (274, 437)]],
        "corridors": [rect_poly(225, 375, 333, 465), rect_poly(224, 465, 285, 511), rect_poly(205, 490, 224, 511),
                      rect_poly(228, 511, 258, 554),
                      rect_poly(205, 300, 280, 375),  # trem ka Amfiteatrima
                      rect_poly(335, 397, 420, 430)],  # prolaz ka NB-u
        "rooms": [
            R(155, 210, 375, 430, k("001A"), door=(182.5, 430)), R(210, 225, 375, 430),
            *col(155, 205, [(432, 472, None), (472, 511, k("003"))]),
            R(205, 224, 447, 490),
            R(155, 228, 511, 554, k("004"), door=(215, 511)), R(258, 335, 511, 554, k("005"), door=(270, 511)),
            R(285, 333, 465, 482, k("018"), door=(285, 473)), R(297, 333, 482, 511, k("006"), door=(297, 496)),
        ],
        "entrances": [(228, 554, 258, 560)],
        "stairs": STAIRS,
        "lifts": LIFTS,
        "points": [("ULAZ", (243, 554), "ULAZ"), ("PROLAZ-AMF", (236, 376), "PROLAZ"),
                   ("PROLAZ-NB", (333, 413), "PROLAZ")],
    }


def floor_1():
    return {
        "title": "1. sprat",
        # Ka prolazu u NB pravim uglovima (korisnik nacrtao, teren 27.10.2026): od prstena pravo do visine prolaza, pa pravo do
        # prolaza - do tada koso od ugla prstena preko stepeništa, a vrata 5MX-1/5MX-2 takođe koso na ugao.
        "paths": RING + [[(274, 437), (274, 422), (326, 422)]],
        "corridors": [rect_poly(207, 432, 287, 511), rect_poly(263, 402, 333, 462),
                      rect_poly(335, 404, 420, 440)],  # zastakljen prolaz ka NB-u (teren 01.10.2026)
        "rooms": [
            # Vrata na delu zida uz hodnik (pravi ugao do prstena), ne na sredini zida (27.10.2026; PRETPOSTAVKA - mesto vrata nije
            # na snimcima). 103 ne dodiruje hodnik - ostaje kako je.
            R(155, 192, 375, 432, k(103)), R(192, 227, 375, 432, k(101), door=(215, 432)), R(227, 263, 375, 432, k(102)),
            *row(375, 402, [(263, 298, "5MX-1"), (298, 333, "5MX-2")]),
            *col(155, 207, [(432, 472, k(104)), (472, 511, k(105))]),
            R(155, 228, 511, 554, k(106), door=(217.5, 511)), R(228, 263, 511, 554, k(107)),
            R(263, 333, 511, 554, k(108), door=(270, 511)),
            R(287, 333, 465, 482, k(110), door=(287, 473)), R(287, 333, 482, 511, k("109A"), door=(287, 496)),
        ],
        "stairs": STAIRS,
        "lifts": LIFTS,
        # Veza sa NB-1-PROLAZ je u nb.json (indoorLinks).
        "points": [("PROLAZ-NB", (333, 422), "PROLAZ")],
    }


def floor_2():
    return {
        "title": "2. sprat",
        "paths": RING,
        "corridors": [rect_poly(207, 427, 287, 511), rect_poly(262, 420, 333, 462)],
        "rooms": [
            R(155, 225, 375, 427, k("204/205"), door=(215, 427)), R(225, 262, 375, 427, k(203)),
            R(262, 333, 375, 420, k(202), door=(270, 420)),
            *col(155, 207, [(427, 465, k(206)), (465, 511, k(207))]),
            *five(511, 554, [208, 209, 210, 211, 213], wall=511),
            *toilets(462, 473),
        ],
        "stairs": STAIRS,
        "lifts": LIFTS,
    }


def floor_3():
    return {
        "title": "3. sprat",
        "paths": RING,
        "corridors": [rect_poly(207, 427, 287, 511), rect_poly(287, 427, 333, 462)],
        "rooms": [
            R(155, 333, 375, 427, k(301)),
            *col(155, 207, [(427, 465, k(302)), (465, 511, k(303))]),
            R(155, 228, 511, 554, k(304), door=(215, 511)), R(228, 263, 511, 554, k(305)), R(263, 333, 511, 554, k(306), door=(270, 511)),
            *toilets(462, 475),
        ],
        "stairs": STAIRS,
        "lifts": LIFTS,
    }


def floor_5():
    plan = typical(5)
    # Niša iznad prstena (x 263-298) je hodnik: krak u nišu, vrata 502 i 501 sa strane niše pod pravim uglom (27.10.2026; do tada
    # koso na ugao prstena - 501 preko stepeništa). PRETPOSTAVKA - mesta vrata nisu na snimcima.
    plan["paths"] = RING + [[(269, 437), (269, 385)]]
    plan["rooms"] = [
        *five(375, 427, [505, 504, 503, None, 501], wall=427),
        R(275, 297, 407, 427, k(502), door=(275, 417)),
        *col(155, 207, [(427, 465, k(506)), (465, 511, k(507))]),
        *five(511, 554, [508, 509, 510, 511, 512], wall=511),
        R(287, 333, 482, 511, k(500), door=(287, 496)),
        *toilets(),
    ]
    plan["rooms"] = [r for r in plan["rooms"] if r["rect"] != (263, 375, 298, 427)]
    plan["rooms"][3]["doors"] = [(298, 391)]  # 501
    plan["corridors"].append(rect_poly(263, 375, 298, 427))
    return plan


PLANS = {0: floor_0(), 1: floor_1(), 2: floor_2(), 3: floor_3(), 4: typical(4), 5: floor_5(),
         **{f: typical(f) for f in range(6, 10)}}
for _plan in PLANS.values():
    _plan["spiral"] = {"S": SPIRAL}


def build():
    return build_graph(BUILDING, PLANS, stairs={"S": FLOORS}, lifts={"L": FLOORS})


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
