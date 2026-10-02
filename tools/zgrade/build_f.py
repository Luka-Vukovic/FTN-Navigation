"""
F-blok: crteži prizemlja ... III sprata i unutrašnji graf.

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/zgrade/build_f.py

Pravi res/drawable/floor_plan_f_{0..3}.xml i assets/f.json (isti format kao nb.json).

Izvor: fotografije evakuacionih planova sva 4 nivoa (teren 02.10.2026, teren/izveštaj2/, van gita; II sprat je
mutan). Perspektiva je ispravljena po spoljnom zidu zgrade (4 ugla) u pravougaonik 600 x 163 px = OSM obris F-bloka
(60,8 x 16,5 m), pa je 1 px plana = 1 px zajedničkog sistema (common.py, M_PER_PX). Pregrade padaju na mrežu od ~25
px (2,5 m).

Orijentacija: plan je u koordinatama evakuacionog plana - dole sever (strana ka Amfiteatrima: stepenište, pasarela),
levo istok, desno zapad, gore jug. Korisnik (02.10.2026): stepenište dole na ekranu je prirodnije za nekog ko se
popne na sprat (prva verzija istog dana je bila u zajedničkom sistemu - uzak i visok plan, gore zapad). Zato F NIJE
u zajedničkom sistemu NB/AMF/Kule: u njega ga prevodi to_shared (x = -130 + y_F, y = 433 - x_F, rotacija za 90°),
a build_campus.py pravi smeštaj sa rotacijom NB - 90°.

Brojevi sala NISU na planovima. Pravilo (korisnik, dok ne dobije tačan raspored): brojanje kreće od prostorije
levo od stepeništa (gledano na planu, kad se stigne na sprat), pa u smeru kazaljke na satu: donji red nalevo,
gornji red nadesno, desni kraj nadole. Toaleti i ostave nisu brojani. Tako ispadaju F 315, 317, 318, 319 (iz
rasporeda) velike sobe od 5 m u gornjem redu III sprata, a F 208 donja leva ugaona soba II sprata - PRETPOSTAVKA.

Veza sa kampusom: samo pasarela iz Amfiteatara (korisnik: "uglavnom samo severni (prolaz ka amfiteatrima), ne znam
da li se koristi stvarno neki spoljni ulaz"). Pasarela stiže na međunivo glavnog stepeništa (teren 01.10.2026: dole
prizemlje, gore I sprat) - veza sa oba nivoa, kao trem Kule u Amfiteatrima. Spoljne stepenice na severu (prizemlje,
sredina zgrade) su samo nacrtane; evakuacioni izlaz na zapadnom kraju se ne crta i ne koristi.
"""

import argparse
from pathlib import Path

from common import R, build_graph, write_all

VX, VY, VW, VH = VIEWPORT = (-15, -15, 630, 230)
WALL = (0, 0, 600, 163)  # OSM obris (60,8 x 16,5 m)
BUILDING = "F"
FLOORS = [0, 1, 2, 3]


def to_shared(p):
    """Tačka plana F-bloka -> zajednički sistem NB/AMF/Kule (za smeštaj u kampus i provere)."""
    return (-130 + p[1], 433 - p[0])


def box(x0, x1, y0, y1):
    """Pravougaonik za crtež (x0, y0, x1, y1), zadat kao R: x0..x1 duž zgrade, y0..y1 poprečno."""
    return (x0, y0, x1, y1)


def poly(*pts):
    return list(pts)


def rooms(floor, y0, y1, spans):
    """Red soba: [(x0, x1, broj ili None)]; broj 15 na 3. spratu -> "F 315"."""
    return [R(a, b, y0, y1, f"F {floor}{n:02d}" if n else None) for a, b, n in spans]


STAIRS_RECT = (402, 452, 114, 160)  # glavno stepenište, uz donji (severni) zid
PASSAGE = (470, 163)  # pasarela ka Amfiteatrima, na međunivou stepeništa (prizemlje i I sprat)
CAMPUS_LINKS = [("K-P-AMF-F", "F-0-PROLAZ-AMF"), ("K-P-AMF-F", "F-1-PROLAZ-AMF")]


def stairs(rect=STAIRS_RECT):
    x0, x1, y0, y1 = rect
    return [("S", box(x0, x1, y0, y1), ((x0 + x1) / 2, 125))]


def floor_0():
    return {
        "title": "Prizemlje",
        "paths": [poly((3, 97), (597, 97)), poly((205, 72), (295, 72)), poly((250, 72), (250, 97)),
                  poly((353, 72), (443, 72)), poly((398, 72), (398, 97))],
        "corridors": [
            poly((0, 80), (600, 80), (600, 113), (0, 113)),
            poly((200, 63), (299, 63), (299, 80), (200, 80)),  # niša sa 4 sobe
            poly((348, 63), (448, 63), (448, 80), (348, 80)),  # niša sa 4 sobe
            poly((250, 113), (299, 113), (299, 163), (250, 163)),  # predvorje spoljnih stepenica
            poly((348, 113), (402, 113), (402, 163), (385, 163)),  # proširenje ka stepeništu
            poly((452, 113), (489, 113), (489, 163), (452, 163)),  # ka pasareli
        ],
        "entrances": [box(452, 489, 163, 205)],  # pasarela
        "steps": [box(250, 299, 163, 205)],  # spoljne stepenice (sever)
        "rooms": [
            *rooms(0, 0, 80, [(0, 51, 8), (51, 100, 9), (100, 151, 10), (151, 200, 11)]),
            *rooms(0, 0, 63, [(200, 225, 12), (225, 250, 13), (250, 275, 14), (275, 299, 15)]),
            *rooms(0, 0, 80, [(299, 348, 16)]),
            *rooms(0, 0, 63, [(348, 373, 17), (373, 398, 18), (398, 422, 19), (422, 448, 20)]),
            *rooms(0, 0, 80, [(448, 498, 21), (498, 548, 22), (548, 600, 23)]),
            *rooms(0, 113, 163, [(0, 51, 7), (51, 100, 6), (100, 151, 5), (151, 200, 4), (200, 250, 3),
                                 (299, 323, 2), (323, 348, 1), (489, 548, 24), (548, 600, None)]),
        ],
        "stairs": stairs(),
        "points": [("PROLAZ-AMF", PASSAGE, "PROLAZ")],
    }


def floor_1():
    return {
        "title": "I sprat",
        "paths": [poly((3, 97), (597, 97))],
        "corridors": [
            poly((0, 82), (600, 82), (600, 113), (0, 113)),
            poly((352, 113), (405, 113), (405, 163), (385, 163)),
            poly((453, 113), (489, 113), (489, 163), (453, 163)),
        ],
        "entrances": [box(453, 489, 163, 205)],
        "rooms": [
            *rooms(1, 0, 82, [(0, 51, 14), (51, 75, 15), (75, 100, 16), (100, 125, 17), (125, 150, 18),
                              (150, 175, 19), (175, 200, 20), (200, 225, 21), (225, 250, 22), (250, 275, 23),
                              (275, 300, 24), (300, 400, 25), (400, 500, 26), (500, 600, 27)]),
            *rooms(1, 113, 163, [(0, 51, 13), (51, 75, 12), (75, 100, 11), (100, 125, 10), (125, 150, 9),
                                 (150, 175, 8), (175, 200, 7), (200, 225, 6), (225, 250, 5), (250, 275, 4),
                                 (275, 300, 3), (300, 325, 2), (325, 352, 1),
                                 (489, 509, 30), (509, 550, None), (550, 572, 29), (572, 600, 28)]),
        ],
        "stairs": stairs((405, 453, 114, 160)),
        "points": [("PROLAZ-AMF", PASSAGE, "PROLAZ")],
    }


def floor_2():
    return {
        "title": "II sprat",
        "paths": [poly((3, 98), (597, 98))],
        "corridors": [
            poly((0, 82), (600, 82), (600, 114), (0, 114)),
            poly((373, 114), (402, 114), (402, 163), (390, 163)),
            poly((452, 114), (489, 114), (489, 163), (452, 163)),  # podest stepeništa
        ],
        "rooms": [
            *rooms(2, 0, 82, [(0, 50, 9), (50, 99, 10), (99, 122, 11), (122, 149, 12), (149, 172, 13),
                              (172, 199, 14), (199, 247, 15), (247, 270, 16), (270, 297, 17), (297, 347, 18),
                              (347, 397, 19), (397, 421, 20), (421, 448, 21), (448, 498, 22), (498, 548, 23),
                              (548, 600, 24)]),
            *rooms(2, 114, 163, [(0, 50, 8), (50, 100, 7), (100, 149, 6), (149, 198, 5), (198, 248, 4),
                                 (248, 322, 3), (322, 347, 2), (347, 373, 1),
                                 (489, 509, 27), (509, 548, None), (548, 573, 26), (573, 600, 25)]),
        ],
        "stairs": stairs(),
    }


def floor_3():
    return {
        "title": "III sprat",
        "paths": [poly((3, 90), (374, 90), (390, 98), (597, 98))],
        "corridors": [
            poly((0, 82), (600, 82), (600, 114), (398, 114), (398, 160), (387, 160), (387, 132), (374, 132),
                 (374, 98), (0, 98)),
            poly((446, 114), (465, 114), (465, 160), (446, 160)),  # podest stepeništa
        ],
        "rooms": [
            *rooms(3, 0, 82, [(0, 52, 9), (52, 101, 10), (101, 126, 11), (126, 151, 12), (151, 175, 13),
                              (175, 201, 14), (201, 251, 15), (251, 300, 16), (300, 350, 17), (350, 400, 18),
                              (400, 450, 19), (450, 474, 20), (474, 548, 21), (548, 600, 22)]),
            *rooms(3, 98, 163, [(0, 52, 8), (52, 101, 7), (101, 151, 6), (151, 201, 5), (201, 251, 4),
                                (251, 301, 3), (301, 350, 2), (350, 374, 1)]),
            R(374, 387, 132, 163),  # ostava kod stepeništa
            *rooms(3, 114, 163, [(465, 496, 24), (496, 537, None), (559, 600, 23)]),
        ],
        "stairs": stairs((398, 446, 114, 160)),
    }


PLANS = {0: floor_0(), 1: floor_1(), 2: floor_2(), 3: floor_3()}


def build():
    return build_graph(BUILDING, PLANS, stairs={"S": FLOORS}, lifts={})


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.parse_args()
    g = build()
    write_all(g, PLANS, VIEWPORT, WALL, "F-blok", "build_f.py", "floor_plan_f", "F-0-PROLAZ-AMF", CAMPUS_LINKS,
              source="fotografija evakuacionih planova (teren 02.10.2026)")


if __name__ == "__main__":
    main()
