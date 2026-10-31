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

from common import STEPS, TOALET, Flights, R, build_graph, write_all

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


PASSAGE = (470, 163)  # pasarela ka Amfiteatrima, na međunivou stepeništa (prizemlje i I sprat)
# Pasarela stiže na podest između P i I - do oba sprata pola sprata stepenicama (STEPS).
CAMPUS_LINKS = [("K-P-AMF-F", "F-0-PROLAZ-AMF", STEPS), ("K-P-AMF-F", "F-1-PROLAZ-AMF", STEPS)]


# Krakovi glavnog stepeništa (31.10.2026; fotografija iz hola prizemlja teren/izveštaj9/IMG_20261010_120816.jpg i evakuacioni
# planovi): dvokrako, sa okretom za 180° na međupodestu. Sa sprata (hol, istočna strana stepeništa) severni krak (uz severni zid,
# na planu dole) vodi naviše ka zapadu na međupodest; sa njega južni krak nazad ka istoku na sledeći sprat. Južni krak sa sprata
# vodi naniže. Pasarela ka Amfiteatrima je na severnoj strani međupodesta između P i I: iz prizemlja pravo ispred kraja kraka
# naviše, sa I sprata sa strane posle kraka naniže. Do tada je stepenište bilo jedan čvor u sredini kutije, a pasarela je sa I
# sprata išla pravo u hodnik - teren 31.10.2026 (107 -> F 214): sa pasarele uz krak na I sprat, a tačka je išla hodnikom I
# sprata ka istoku, pa je posle okreta na I "sprat II" došao prerano (promašaj 6 m); korisnik: "treba izmeniti i stepenice u F-bloku".
LANDING_X = 490  # zapadni kraj međupodesta


def flights(x0, x1, land=LANDING_X, draw_up=True, draw_down=True):
    """Krakovi između x0 (hol) i x1, međupodest x1..land; putanja: dno severnog kraka, okret, dno južnog kraka (sprat)."""
    mid = (x1 + land) / 2
    return Flights(up=(x0, 139, x1, 161), down=(x0, 115, x1, 137), landing=(x1, 113, land, 163),
                   path=[(x0, 150), (mid, 150), (mid, 126), (x0, 126)], draw_up=draw_up, draw_down=draw_down,
                   floor_landing=True)


def stairs(x0):
    """Čvor stepeništa na dnu severnog kraka (ka spratu iznad); dno južnog kraka je čvor S-D (common.build_floor)."""
    return [("S", None, (x0, 150))]


def hall(x, y_corridor):
    """Put kroz hol do oba kraka (pod pravim uglom na hodnik; krakovi se kače vodoravno sa njega)."""
    return [poly((x, y_corridor), (x, 150))]


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
                  poly((378, 71), (422, 71)), poly((388, 71), (388, 96))] + hall(385, 96),
        "corridors": [
            poly((74, 79), (600, 79), (600, 101), (560, 101), (560, 113), (74, 113)),  # hodnik (zapadni kraj ka toaletu)
            poly((225, 59), (300, 59), (300, 79), (225, 79)),  # niša 010A-C
            poly((375, 64), (425, 64), (425, 79), (375, 79)),  # niša 011C/D
            poly((350, 113), (400, 113), (400, 163), (350, 163)),  # hol ispred stepeništa
            poly((250, 163), (300, 163), (300, 177), (250, 177)),  # izlaz iz 002 na spoljne stepenice
        ],
        "entrances": [box(455, 491, 163, 205)],  # pasarela (IZLAZ)
        # Krakovi: severni gore (na međupodest), južni dole (flights); spoljne stepenice kod 002.
        "steps": [box(249, 302, 177, 205)],
        "flights": {"S": flights(400, 454)},
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
        # Pasarela pravo sa hodnika (kao do sada). Probano 10.10.2026 (pasarela iz hola preko kraka, bez crteža krakova) i ponovo
        # 31.10.2026 (sa krakovima, pasarela pravo ispred kraja kraka naviše): replay 04.10. (F -> AMF) ne prelazi - korisnik je sa
        # "Ovde sam" u hodniku kod x 468 išao ravnim hodom (znak ~0) pravo ka pasareli, 10 koraka. Na terenu su prelazi sa ovim
        # grafom radili ("Ok su bili prelazi").
        "stairs": stairs(400),
        "points": [("PROLAZ-AMF", PASSAGE, "PROLAZ")],
    }


def floor_1():
    return {
        "title": "I sprat",
        "paths": [poly((3, 97), (597, 97))] + hall(390, 97),
        "corridors": [
            poly((0, 82), (600, 82), (600, 113), (0, 113)),
            poly((352, 113), (405, 113), (405, 163), (385, 163)),
            poly((453, 113), (489, 113), (489, 163), (453, 163)),
        ],
        "flights": {"S": flights(405, 453)},
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
        "stairs": stairs(405),
        # Sa I sprata niz južni krak na međupodest, pa sa strane na pasarelu - ivica samo za rutu (kao NB I sprat; lociranje:
        # PdrLocator, prolaz sa kraka).
        "points": [("PROLAZ-AMF", PASSAGE, "PROLAZ", ("S-D", [], STEPS))],
    }


def floor_2():
    return {
        "title": "II sprat",
        # 208 je na kraju hodnika, preko cele širine (tabla II sprata, teren/izveštaj3/IMG_20261003_121332.jpg; korisnik
        # 29.10.2026: "208 je zapravo pravo ispred, a ne sa leve strane"): hodnik se završava u nju, vrata pravo na kraju.
        # Gornji red počinje sa 209 odmah uz 208 (korisnik 30.10.2026: "F-209 ima svoja vrata desno, i odande se nastavlja
        # brojanje ... 207 je levo, 208 pravo, 209 desno"; tabla: 209-223 pa 224/225). Do tada je 209 bila ugao iza kraja
        # hodnika sa vratima na zidu 210 - "fizički nemoguće doći". 224/225 sa table na crtežu nema mesta (red ima 15 soba).
        "paths": [poly((51, 98), (597, 98))] + hall(388, 98),
        "corridors": [
            poly((50, 82), (600, 82), (600, 114), (50, 114)),
            poly((373, 114), (402, 114), (402, 163), (390, 163)),
            poly((452, 114), (489, 114), (489, 163), (452, 163)),  # podest stepeništa
        ],
        "flights": {"S": flights(402, 452)},
        "rooms": [
            *rooms(2, 0, 82, [(50, 99, 9), (99, 122, 10), (122, 149, 11), (149, 172, 12),
                              (172, 199, 13), (199, 247, 14), (247, 270, 15), (270, 297, 16), (297, 347, 17),
                              (347, 397, 18), (397, 421, 19), (421, 448, 20), (448, 498, 21), (498, 548, 22),
                              (548, 600, 23)]),
            R(0, 50, 0, 163, "F 208", door=(50, 98)),
            *rooms(2, 114, 163, [(50, 100, 7), (100, 149, 6), (149, 198, 5), (198, 248, 4),
                                 (248, 322, 2), (322, 347, 1), (347, 373, 0),  # 202 = i 203 (tabla: dve sobe)
                                 (489, 509, 26), (509, 548, TOALET), (548, 573, 27), (573, 600, 28)]),
        ],
        "stairs": stairs(402),
    }


def floor_3():
    return {
        "title": "III sprat",
        # Uži hodnik levo: prelaz na širi pod pravim uglom (27.10.2026; do tada koso 374 -> 390).
        "paths": [poly((3, 90), (382, 90), (382, 98), (597, 98))] + hall(392, 98),
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
        # Poslednji sprat: samo krak naniže (južni); međupodest ispod je uži (do sobe 326).
        "flights": {"S": flights(398, 446, land=465, draw_up=False)},
        "stairs": stairs(398),
    }


# Vrata I-III sprata po evakuacionim planovima (30.10.2026, korisnik: "vrata i za F-blok"; teren/izveštaj2/IMG_20261002_103655 (I),
# _104205 (II, mutna), _104108 (III), perspektiva ispravljena po 4 ugla zgrade na 600 x 163). I i III: prekidi u zidu i lukovi otvaranja;
# II: prekidi se ne vide, pa strelice evakuacije (na I spratu stoje tačno ispred vrata, do 1 px). Samo gde je razlika veća od ~0,4 m;
# ostale sobe imaju vrata na sredini zida i to se slaže sa planom. 214: dve strelice -> dvoja vrata. 209 ima takođe dve strelice
# (63, 87), ali korisnik (30.10.2026): "F-209 ima svoja vrata desno" na kraju hodnika (jedna) - ostaju samo vrata uz kraj hodnika.
# III: ispred 320 je pregrada sa vratima preko hodnika (x ~525) - nije nacrtana; II: kraj kod 223 je nejasan - ostaje.
DOORS = {
    1: {"F 124": [(388, 82)], "F 125": [(486, 82)], "F 126": [(583, 82)],
        "F 1": [(494, 113)], "F 2": [(556, 113)], "F 3": [(590, 113)]},
    2: {"F 209": [(63, 82)],"F 214": [(212, 82), (237, 82)], "F 221": [(485, 82)], "F 222": [(511, 82)],
        "F 207": [(64, 114)], "F 206": [(137, 114)], "F 205": [(163, 114)], "F 204": [(212, 114)], "F 202": [(314, 114)],
        "F 226": [(493, 114)], "F 227": [(556, 114)], "F 228": [(591, 114)]},
    3: {"F 309": [(71, 82)], "F 319": [(486, 82)], "F 320": [(558, 82)],
        "F 308": [(40, 98)], "F 307": [(72, 98)], "F 326": [(487, 114)]},
}


def with_doors(f, plan):
    """Vrata iz [DOORS] za sale sprata f."""
    doors = DOORS.get(f, {})
    for room in plan["rooms"]:
        if room["name"] in doors:
            room["doors"] = doors[room["name"]]
    assert set(doors) <= {r["name"] for r in plan["rooms"]}, set(doors) - {r["name"] for r in plan["rooms"]}
    return plan


PLANS = {f: with_doors(f, plan) for f, plan in {0: floor_0(), 1: floor_1(), 2: floor_2(), 3: floor_3()}.items()}


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
