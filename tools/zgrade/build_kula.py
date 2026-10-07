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

STAIRS = [("S", (285, 427, 333, 460), (280, 443))]
LIFTS = [("L", (224, 447, 263, 478), (243, 484))]
RING = [[(215, 437), (274, 437), (274, 495), (215, 495), (215, 437)]]  # hodnik oko lifta
CAMPUS_LINKS = [("K-U-KULA-1", "KULA-0-ULAZ"), ("K-P-NB-KULA", "KULA-0-PROLAZ-NB"), ("K-P-AMF-KULA", "KULA-0-PROLAZ-AMF")]

X5 = [155, 192, 227, 263, 298, 333]  # pet soba u redu (gore i dole)


def k(n):
    return f"Kula {n}"


def five(y0, y1, numbers):
    return row(y0, y1, [(a, b, k(n) if n else None) for a, b, n in zip(X5, X5[1:], numbers)])


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
            *five(375, 427, [n + 5, n + 4, n + 3, n + 2, n + 1]),
            *col(155, 207, [(427, 465, k(n + 6)), (465, 511, k(n + 7))]),
            *five(511, 554, [n + 8, n + 9, n + 10, n + 11, n + 12]),
            R(287, 333, 482, 511, k(n + 15), door=(287, 496)),
            *toilets(),
        ],
        "stairs": STAIRS,
        "lifts": LIFTS,
    }


def floor_0():
    return {
        "title": "Prizemlje",
        # Levo od lifta je soba, pa hodnik nije prsten.
        "paths": [[(210, 437), (274, 437), (274, 495), (205, 495)]],
        "corridors": [rect_poly(225, 375, 333, 465), rect_poly(224, 465, 285, 511), rect_poly(205, 490, 224, 511),
                      rect_poly(228, 511, 258, 554),
                      rect_poly(205, 300, 280, 375),  # trem ka Amfiteatrima
                      rect_poly(335, 397, 420, 430)],  # prolaz ka NB-u
        "rooms": [
            R(155, 210, 375, 430, k("001A")), R(210, 225, 375, 430),
            *col(155, 205, [(432, 472, None), (472, 511, k("003"))]),
            R(205, 224, 447, 490),
            R(155, 228, 511, 554, k("004")), R(258, 335, 511, 554, k("005")),
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
        "paths": RING,
        "corridors": [rect_poly(207, 432, 287, 511), rect_poly(263, 402, 333, 462),
                      rect_poly(335, 404, 420, 440)],  # zastakljen prolaz ka NB-u (teren 01.10.2026)
        "rooms": [
            *row(375, 432, [(155, 192, k(103)), (192, 227, k(101)), (227, 263, k(102))]),
            *row(375, 402, [(263, 298, "5MX-1"), (298, 333, "5MX-2")]),
            *col(155, 207, [(432, 472, k(104)), (472, 511, k(105))]),
            *row(511, 554, [(155, 228, k(106)), (228, 263, k(107)), (263, 333, k(108))]),
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
            R(155, 225, 375, 427, k("204/205")), R(225, 262, 375, 427, k(203)), R(262, 333, 375, 420, k(202)),
            *col(155, 207, [(427, 465, k(206)), (465, 511, k(207))]),
            *five(511, 554, [208, 209, 210, 211, 213]),
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
            *row(511, 554, [(155, 228, k(304)), (228, 263, k(305)), (263, 333, k(306))]),
            *toilets(462, 475),
        ],
        "stairs": STAIRS,
        "lifts": LIFTS,
    }


def floor_5():
    plan = typical(5)
    plan["rooms"] = [
        *five(375, 427, [505, 504, 503, None, 501]),
        R(275, 297, 407, 427, k(502)),
        *col(155, 207, [(427, 465, k(506)), (465, 511, k(507))]),
        *five(511, 554, [508, 509, 510, 511, 512]),
        R(287, 333, 482, 511, k(500), door=(287, 496)),
        *toilets(),
    ]
    plan["rooms"] = [r for r in plan["rooms"] if r["rect"] != (263, 375, 298, 427)]
    plan["corridors"].append(rect_poly(263, 375, 298, 427))
    return plan


PLANS = {0: floor_0(), 1: floor_1(), 2: floor_2(), 3: floor_3(), 4: typical(4), 5: floor_5(),
         **{f: typical(f) for f in range(6, 10)}}


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
