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

Brojevi sala NISU na evakuacionim planovima. Teren 03.10.2026: table sa brojevima na I i II spratu (šematske - sve
sobe iste veličine) i 3D prikaz na njima (III sprat i delovi ostalih), teren/izveštaj3/IMG_20261003_1212*.jpg. Obrazac:
od sobe levo (istočno) od stepeništa donjim (severnim) redom do istočnog kraja (x01..x13 na I, 200..208 na II, 301..308
na III), pa gornjim (južnim) redom nazad ka zapadu (114..126, 209..224, 309..320); jugoistočni ugao je na I i III deo
istočne krajnje sobe (na tabli I sprata 113 zauzima ceo kraj). Zapadno od stepeništa: I "1", toalet, "2", "3"; II
226-228; III 326. Toalet na II i III spratu (TOALET) je soba bez broja na istom mestu kao toalet I sprata - PRETPOSTAVKA
(07.10.2026). Ranije pravilo korisnika (od sobe levo od stepeništa u smeru kazaljke) je za donji red bilo tačno,
gornji red je bio pomeren za jednu sobu.
II sprat: tabla ima 8 soba između stepeništa i istočnog kraja (200-207), crtež 7 - crtež je verovatno tačniji (korisnik),
a jedina neobična soba crteža je široka 74 px (ostale 25/50): tu tabla ima dve sobe -> "F 202" sa aliasom 203
(PRETPOSTAVKA). 224/225 je jedna soba (jugozapadni ugao, potvrđuje 3D). III sprat: na 3D-u piše "318" dvaput - druga je
319 (u rasporedu postoji); uska soba (450-474) nema broj. Prizemlje: od 10.10.2026 precrtano sa jasnije fotografije
evakuacionog plana, oznake upisao korisnik (vidi floor_0). Ranije (02.-10.10.) šematski crtež sa nišama po 4 sobe i
brojevima po obrascu - bio je pogrešan (korisnik: "nije baš bila ispravna mapa za F-blok prizemlje").

Veza sa kampusom: samo pasarela iz Amfiteatara (korisnik: "uglavnom samo severni (prolaz ka amfiteatrima), ne znam
da li se koristi stvarno neki spoljni ulaz"). Pasarela stiže na međunivo glavnog stepeništa (teren 01.10.2026: dole
prizemlje, gore I sprat) - veza sa oba nivoa, kao trem Kule u Amfiteatrima. Spoljne stepenice na severu (prizemlje,
sredina zgrade) su samo nacrtane; evakuacioni izlaz na zapadnom kraju se ne crta i ne koristi.
"""

import argparse
from pathlib import Path

from common import STEPS, TOALET, R, build_graph, write_all

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
    """
    Red soba: [(x0, x1, broj ili None ili gotov naziv ili TOALET)]; broj 15 na 3. spratu -> "F 315", 0 na 2. -> "F 200".
    """
    def name(n):
        if n is None or isinstance(n, str):
            return n
        return f"F {floor}{n:02d}"
    return [R(a, b, y0, y1, amenity=n) if n == TOALET else R(a, b, y0, y1, name(n)) for a, b, n in spans]


STAIRS_RECT = (402, 452, 114, 160)  # glavno stepenište, uz donji (severni) zid
PASSAGE = (470, 163)  # pasarela ka Amfiteatrima, na međunivou stepeništa (prizemlje i I sprat)
# Pasarela stiže na podest između P i I - do oba sprata pola sprata stepenicama (STEPS).
CAMPUS_LINKS = [("K-P-AMF-F", "F-0-PROLAZ-AMF", STEPS), ("K-P-AMF-F", "F-1-PROLAZ-AMF", STEPS)]


def stairs(rect=STAIRS_RECT):
    x0, x1, y0, y1 = rect
    return [("S", box(x0, x1, y0, y1), ((x0 + x1) / 2, 125))]


def floor_0():
    """
    Prizemlje precrtano sa jasnije fotografije evakuacionog plana (teren 10.10.2026, teren/izveštaj9/IMG_20261010_121736.jpg,
    perspektiva ispravljena po 4 spoljna ugla na 600 x 163). Oznake soba: korisnik ih je upisao na tu fotografiju
    ("fp obelezeno.png"; korisnik: "nije baš bila ispravna mapa za F-blok prizemlje"). Slaže se i sa 3D prikazom sa table
    (04.10.2026): južni red od zapada 012 ... 007, istočni kraj bez broja.
    Istočni kraj: prostorija (ne zna se kakva) sa vratima ka hodniku, iz nje dve sobe bez broja - prva verzija istog dana ju
    je crtala kao predvorje (deo hodnika), a niše kao otvorene ka hodniku; korisnik: "pravougaonik levo predstavlja prostoriju
    ... druga dva su mali hodnici koji vode do drugih učionica, ograđeni su zidom i postoje vrata do njih". Južni red: 007-009,
    010 (vrata u nišu), niša sa 010A-C, 011A, 011B (vrata u nišu), niša sa 011C/D, 011E (vrata u nišu), 012, 012A, soba bez
    broja. Severni red: soba bez broja, 006-003, 002 (izlaz na spoljne stepenice), 001B, 001A, hol, stepenište, međupodest sa
    pasarelom ("IZLAZ"), soba bez broja, toaleti.
    Stepenište (fotografija sa terena IMG_20261010_120816 i strelice na planu): iz hola severni krak vodi gore, ka zapadu, na
    međupodest odakle ide pasarela; južni krak vodi dole (ispod nema plana). Na I sprat se ide sa međupodesta.
    """
    return {
        "title": "Prizemlje",
        # Niše su zatvoreni mali hodnici sa jednim vratima ka glavnom hodniku (korisnik, 10.10.2026 uveče; na planu: niša
        # 010A-C vrata desno, x 284-294; niša 011C/D vrata levo, x 383-393).
        "paths": [poly((77, 96), (597, 96)), poly((228, 69), (296, 69)), poly((289, 69), (289, 96)),
                  poly((378, 71), (422, 71)), poly((388, 71), (388, 96))],
        "corridors": [
            poly((74, 79), (600, 79), (600, 101), (560, 101), (560, 113), (74, 113)),  # hodnik (zapadni kraj ka toaletu)
            poly((225, 59), (300, 59), (300, 79), (225, 79)),  # niša 010A-C
            poly((375, 64), (425, 64), (425, 79), (375, 79)),  # niša 011C/D
            poly((350, 113), (400, 113), (400, 163), (350, 163)),  # hol ispred stepeništa
            poly((250, 163), (300, 163), (300, 177), (250, 177)),  # izlaz iz 002 na spoljne stepenice
        ],
        "entrances": [box(455, 491, 163, 205)],  # pasarela (IZLAZ)
        # Krakovi: severni gore (na međupodest), južni dole; spoljne stepenice kod 002.
        "steps": [box(400, 454, 140, 160), box(400, 454, 116, 138), box(249, 302, 177, 205)],
        "landings": [box(454, 490, 113, 163)],
        "rooms": [
            # Istočni kraj: prostorija (ne zna se kakva - korisnik: "nije prazan prostor"), vrata ka hodniku u pregradi; iz nje
            # se ulazi u dve krajnje sobe. Donji zid je stepenast (kao na planu).
            {**R(0, 74, 79, 115), "poly": [(0, 79), (74, 79), (74, 112), (50, 112), (50, 115), (0, 115)]},
            R(0, 50, 0, 79, door=(28, 79)),
            # Zidovi niša (zatvoreni mali hodnici).
            R(225, 300, 59, 79),
            R(375, 425, 64, 79),
            # 007 i 006: vrata desno od pregrade istočne prostorije (na planu x 82-92; na sredini zida su padala na pregradu).
            R(50, 100, 0, 79, "F 007", door=(87, 79)),
            *rooms(0, 0, 79, [(100, 150, 8), (150, 200, 9)]),
            R(200, 225, 0, 79, "F 010", door=(225, 70)),
            R(225, 250, 0, 59, "F 010A", door=(237, 59)),
            R(250, 275, 0, 59, "F 010B", door=(262, 59)),
            R(275, 300, 0, 59, "F 010C", door=(288, 59)),
            R(300, 350, 0, 79, "F 011A", door=(314, 79)),
            R(350, 375, 0, 79, "F 011B", door=(375, 71)),
            R(375, 400, 0, 64, "F 011C", door=(387, 64)),
            R(400, 425, 0, 64, "F 011D", door=(413, 64)),
            R(425, 450, 0, 79, "F 011E", door=(425, 71)),
            R(450, 500, 0, 79, "F 012", door=(486, 79)),
            R(500, 550, 0, 79, "F 012A", door=(508, 79)),
            R(550, 600, 0, 79),
            R(0, 50, 115, 163),
            R(50, 100, 113, 163, "F 006", door=(87, 113)),
            *rooms(0, 113, 163, [(100, 150, 5), (150, 200, 4), (200, 250, 3)]),
            R(250, 300, 113, 163, "F 002", door=(260, 113)),
            R(300, 325, 113, 163, "F 001B", door=(314, 113)),
            R(325, 350, 113, 163, "F 001A", door=(332, 113)),
            R(490, 550, 113, 163),
            R(550, 600, 113, 163, door=(560, 113), amenity=TOALET),
        ],
        # Graf kao ranije: čvor stepeništa (ka I spratu) u kutiji stepeništa, pasarela pravo sa hodnika (krak do međupodesta
        # je na vezi sa kampusom, STEPS). Probano 10.10.2026: pasarela iz hola preko kraka i međupodesta (kako je na planu) -
        # replay 04.10. (F -> AMF) više nije prelazio: korisnik je sa "Ovde sam" u hodniku kod x 470 išao pravo ka pasareli,
        # a tačka je zapela. Prelazi su na terenu radili sa ovim grafom ("Ok su bili prelazi"), pa je novo samo crtež.
        "stairs": [("S", None, (427, 125))],
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
            *rooms(1, 0, 82, [(0, 51, None), (51, 75, 14), (75, 100, 15), (100, 125, 16), (125, 150, 17),
                              (150, 175, 18), (175, 200, 19), (200, 225, 20), (225, 250, 21), (250, 275, 22),
                              (275, 300, 23), (300, 400, 24), (400, 500, 25), (500, 600, 26)]),
            *rooms(1, 113, 163, [(0, 51, 13), (51, 75, 12), (75, 100, 11), (100, 125, 10), (125, 150, 9),
                                 (150, 175, 8), (175, 200, 7), (200, 225, 6), (225, 250, 5), (250, 275, 4),
                                 (275, 300, 3), (300, 325, 2), (325, 352, 1),
                                 (489, 509, "F 1"), (509, 550, TOALET), (550, 572, "F 2"), (572, 600, "F 3")]),
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
                              (548, 600, 24)]),  # 224 = i 225
            *rooms(2, 114, 163, [(0, 50, 8), (50, 100, 7), (100, 149, 6), (149, 198, 5), (198, 248, 4),
                                 (248, 322, 2), (322, 347, 1), (347, 373, 0),  # 202 = i 203 (tabla: dve sobe)
                                 (489, 509, 26), (509, 548, TOALET), (548, 573, 27), (573, 600, 28)]),
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
            *rooms(3, 0, 82, [(0, 52, None), (52, 101, 9), (101, 126, 10), (126, 151, 11), (151, 175, 12),
                              (175, 201, 13), (201, 251, 14), (251, 300, 15), (300, 350, 16), (350, 400, 17),
                              (400, 450, 18), (450, 474, None), (474, 548, 19), (548, 600, 20)]),
            *rooms(3, 98, 163, [(0, 52, 8), (52, 101, 7), (101, 151, 6), (151, 201, 5), (201, 251, 4),
                                (251, 301, 3), (301, 350, 2), (350, 374, 1)]),
            R(374, 387, 132, 163),  # ostava kod stepeništa
            *rooms(3, 114, 163, [(465, 496, 26), (496, 537, TOALET), (559, 600, None)]),
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
