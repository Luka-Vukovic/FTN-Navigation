"""
Departman za građevinarstvo i geodeziju (DGG, u OSM-u "Laboratorije za građevinarstvo i geodeziju"): crteži prizemlja ... III
sprata i unutrašnji graf.

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/zgrade/build_dgg.py

Pravi res/drawable/floor_plan_dgg_{0..3}.xml i assets/dgg.json (isti format kao nb.json).

Izvor: fotografije evakuacionih planova P, I, II i III sprata (teren 10.11.2026, teren/izveštaj39/IMG_20261110_1206*, _120514
(+ aaa.jpg - ista slika, korisnik upisao 101 i 105), _120439, _120324; van gita). Suteren nije slikan (korisnik: "čini mi se da
tamo nema nastave" - sve LG sale iz rasporeda su u prizemlju i na I spratu). Perspektiva ispravljena homografijom po 4 spoljna
ugla na spoljni zid plana (0, 0, 294, 162) = OSM obris (way 148672026) u zajedničkom sistemu (px plana NB, 1 px = M_PER_PX):
29,8 m sever-jug x 16,4 m istok-zapad. Pregrade i vrata očitani sa ispravljenih fotografija (mreža 1 m).

Orijentacija: kao plan prizemlja - gore istok (glavni ulaz, "plato preko puta ulice naspram glavnog ulaza"), levo sever, desno
jug, dole zapad. Planovi I i II sprata vise okrenuti za 180° (jezgro gore) i preračunati su; III visi kao prizemlje. Jezgro
(stepenište oko okna, toaleti) je dole, uz zapadni zid - na sva 4 plana na istom mestu. Ulaz sa terenske mape (istočni zid,
29.09.2026) pada 2,2 m od ulaza sa plana. Zajednički sistem je ovaj zarotiran za 180° (to_shared), pa je rotacija smeštaja
rotacija NB + 180° (build_campus.py, dgg_placement).

Prizemlje: glavni ulaz na istoku, unutrašnje stepenice od vrata do hola (crtano kao stepenice, ruta ih prelazi kao hod), portirnica
i razvodni ormar levo od njih, hol, učionice 001 (sever, uz laboratoriju 001b) i 002 (severozapad), laboratorije 003 i 004 i
učionica 005 (jug), toaleti i čajna kuhinja 006 desno od stepeništa. I: 102-104 (sever; 102 i 103 iza vrata "101"), učionice 105 i 106 (istok), veliki
amfiteatar (jug), toaleti. II: kabineti 201-213 + čajna kuhinja 214. III: kabineti 301-312 + čajna kuhinja 313.

Oznake: P, II i III sa planova (čitljive); na I su čitljivi samo nazivi soba (amfiteatar, dve učionice, kancelarije, konferencijska
sala). Brojeve I sprata je korisnik obeležio na terenu 12.11.2026 (stranica "DGG prostorije"): 105 i 106 učionice, 107 amfiteatar,
103 kancelarija direktora, 102 kancelarija sekretara, 104 konferencijska sala; "101" su samo vrata predprostora ka 102 i 103.
Sale su "LG 105" kao u rasporedu, i kabineti ("LG 201"): na vratima nema "LG" (korisnik, 10.11.2026), ali je to oznaka zgrade u
rasporedu - kao "ITC 101" u ITC-u (brojevi 101, 201... se ponavljaju u NB, Kuli i ITC-u, a naziv sale mora biti jedinstven); natpis na
planu bez "LG ". Korisnik: "Neka ostane LG 201, kao sada, pošto je tako u rasporedu".

Lift u oknu u sredini stepeništa, P-III (korisnik, 10.11.2026: "Postoji lift, dosta je slično kao u NB").
"""

import argparse

from common import TOALET, Flights, R, build_graph, rect_poly, write_all

VX, VY, VW, VH = VIEWPORT = (-12, -20, 318, 194)
WALL = (0, 0, 294, 162)  # OSM obris (29,8 x 16,4 m)
BUILDING = "DGG"
FLOORS = [0, 1, 2, 3]

STAIRS_RECT = (98, 107, 147, 160)  # stepenište oko okna (lifta), isto na svim spratovima
LIFT_RECT = (114, 121, 130, 140)
# Stepenište sa planova: tri kraka oko okna lifta u obliku slova U - levi krak (od hola ka zapadnom zidu), donji krak iza lifta
# (popreko, uz zapadni zid) i desni krak (nazad ka holu). Naviše se ide levim krakom uz dva okreta ulevo i stiže se na vrh desnog
# kraka sprata iznad; naniže desnim krakom uz dva okreta udesno (snimak 12.11.2026: azimut telefona 240 -> 140 -> 65° pri penjanju
# P -> I, 260 -> 345 -> 75° pri silasku II -> I). Donji krak je u modelu deo međupodesta (dva okreta po 90° sa ~6 gazišta između -
# PdrLocator LANDING_EXIT_STEPS_BY_STAIR). Korisnik, 12.11.2026: "Stepenice u LG moraju da se poprave" - do tada pravougaonik bez
# krakova (tačka je stajala na čvoru dok se sprat menjao: "delovalo je kao da ne idem po stepenicama, pa se ipak promenio sprat").
# Prizemlje: desni krak vodi u suteren (nije u modelu - nema čvora S-D), ali se crta kao na planu.
# Korisnik, 12.11.2026 uveče: "da budu poput onih NB. Obično ima oko 11 koraka sa krakova, a ima nekih 6 bočno. Ne treba ispred lifta da
# stoje stepenice." -> bočni krakovi od hola (y 107) do ugaonih podesta (y 138): 3,1 m = 11 gazišta po ~0,28 m; donji krak iza lifta
# 1,7 m = 6 gazišta; ispred lifta (između krakova) je pod hola, ne podest stepeništa. Prva verzija (isti dan): krakovi 110..135 / 107..137
# sa planova (evakuacioni planovi crtaju krakove šematski), a ispred lifta podest bojom stepeništa.
STAIR_FOOT = (105, 106)  # dno levog kraka (naviše), u holu
# Peti član pravougaonika kraka = broj gazišta na crtežu (common.treads).
FLIGHTS = Flights(up=(98, 107, 112, 138, 11), down=(130, 107, 145, 138, 11), landing=(98, 138, 113, 158),
                  path=[STAIR_FOOT, (105, 148), (138, 148), (138, 106)])
STAIR_MIDDLE = (113, 140, 130, 155, 6)  # donji krak iza lifta (crtež; u modelu deo međupodesta)
# Desni ugaoni podest i traka ispod donjeg kraka.
STAIR_LANDINGS = [(130, 138, 145, 158), (113, 155, 130, 158)]
LIFT_FRONT = rect_poly(112, 107, 130, 121)  # hol ispred lifta, između krakova
ENTRANCE = (111, 0)  # glavni ulaz, istočni zid
CAMPUS_LINKS = [("K-U-DGG-1", "DGG-0-ULAZ")]


def to_shared(p):
    """Tačka plana DGG -> zajednički sistem NB/AMF/Kule (desno sever, dole istok): plan je zarotiran za 180°."""
    return (-487 - p[0], -1402 - p[1])


def core(steps=()):
    return {"stairs": [("S", STAIRS_RECT, STAIR_FOOT)], "lifts": [("L", LIFT_RECT, (122, 104))], "flights": {"S": FLIGHTS},
            "landings": STAIR_LANDINGS, "steps": [*steps, STAIR_MIDDLE]}


def lg(n):
    return f"LG {n}"


def floor_0():
    return {
        "title": "Prizemlje",
        "paths": [
            [(75, 80), (216, 80)],  # hol
            [(111, 2), (111, 80)],  # od glavnog ulaza niz unutrašnje stepenice
            [(85, 80), (85, 96)],  # ka 002
            [(182, 80), (182, 131)],  # ka toaletima
        ],
        "corridors": [
            [(73, 62), (97, 62), (97, 0), (126, 0), (126, 45), (143, 45), (143, 60), (218, 60), (218, 100), (188, 100),
             (188, 133), (176, 133), (176, 100), (147, 100), (147, 107), (98, 107), (98, 98), (73, 98)],
        ],
        "entrances": [(97, -4, 126, 0)],
        "rooms": [
            R(0, 73, 0, 27),  # 001b - laboratorija (iza 001, bez svojih vrata ka holu)
            R(0, 73, 27, 98, lg("001"), door=(73, 80)),
            R(0, 98, 98, 162, lg("002"), door=(85, 98)),
            R(73, 96, 0, 32),  # glavni razvodni ormar
            R(73, 96, 32, 62, door=(80, 62)),  # 007 portirnica
            R(126, 143, 0, 45),  # vetrobran uz ulaz
            R(143, 218, 0, 60, lg("003"), door=(190, 60)),
            R(218, 294, 0, 81, lg("004"), door=(218, 72)),
            R(218, 294, 81, 162, lg("005"), door=(218, 90)),
            R(147, 176, 100, 133, amenity=TOALET, door=(176, 116)),
            R(147, 188, 133, 162, amenity=TOALET, door=(182, 133)),
            R(188, 207, 100, 133),
            R(188, 218, 133, 162, door=(200, 133)),  # 006 čajna kuhinja
        ],
        **core(steps=[(98, 2, 126, 45)]),  # + unutrašnje stepenice od ulaza do hola
    }


def floor_1():
    # Teren 12.11.2026 (stranica "DGG prostorije" + snimak ekrana sa ucrtanim vratima): "ne postoji prava prostorija 101, već su to
    # samo vrata koja vode do prostorija 102 i 103" - vrata 101 su na x 77 (y 58..81) ka predprostoru (55..77 x 58..81), iz njega
    # 103 (ugao gore levo, do tada "101") i 102; donja leva soba je 104 (do tada "103"). 105, 106 (učionice) i 107 (amfiteatar)
    # potvrđeni.
    return {
        "title": "I sprat",
        # Hodnik kroz vrata "101" u predprostor, do naspram vrata 103 (pravi uglovi).
        "paths": [[(60, 70), (60, 79), (216, 79)], [(87, 79), (87, 116)], [(169, 79), (169, 138)]],
        "corridors": [
            [(77, 58), (218, 58), (218, 100), (177, 100), (177, 140), (162, 140), (162, 100), (147, 100), (147, 107),
             (98, 107), (98, 100), (97, 100), (97, 118), (77, 118)],
        ],
        "rooms": [
            R(55, 77, 58, 81, door=(77, 70)),  # predprostor iza vrata "101"
            R(0, 55, 0, 81, lg("103"), door=(55, 70)),  # kancelarija direktora
            R(0, 77, 81, 118, lg("102"), door=(66, 81)),  # kancelarija sekretara
            R(0, 97, 118, 162, lg("104"), door=(87, 118)),  # konferencijska sala
            R(55, 131, 0, 58, lg("105"), door=(120, 58)),  # učionica
            R(131, 218, 0, 58, lg("106"), door=(157, 58)),  # učionica
            R(218, 294, 0, 162, lg("107"), door=(218, 76)),  # amfiteatar
            R(147, 162, 100, 123, door=(162, 110)),
            R(177, 205, 100, 132, amenity=TOALET, door=(177, 120)),
            R(205, 218, 100, 132),
            R(162, 195, 140, 162),
            R(195, 218, 132, 162),  # čajna kuhinja?
        ],
        **core(),
    }


def floor_2():
    r201 = R(57, 98, 100, 162, lg("201"), door=(75, 108))
    r201["poly"] = [(75, 100), (98, 100), (98, 162), (57, 162), (57, 117), (75, 117)]
    r204 = R(0, 50, 0, 63, lg("204"), door=(50, 58))  # ispod 205 se širi do hola
    r204["poly"] = [(0, 0), (39, 0), (39, 53), (50, 53), (50, 63), (0, 63)]
    return {
        "title": "II sprat",
        "paths": [[(52, 81), (238, 81)], [(66, 81), (66, 115)], [(167, 81), (167, 118)], [(231, 81), (231, 52)],
                  [(198, 81), (198, 104), (232, 104)]],
        "corridors": [
            [(50, 53), (75, 53), (75, 63), (220, 63), (220, 48), (240, 48), (240, 108), (188, 108), (188, 120), (147, 120),
             (147, 107), (98, 107), (98, 100), (75, 100), (75, 117), (57, 117), (57, 100), (50, 100)],
        ],
        "rooms": [
            r204,
            R(39, 75, 0, 53, lg("205"), door=(62, 53)),
            R(75, 113, 0, 63, lg("206"), door=(88, 63)),
            R(113, 148, 0, 63, lg("207"), door=(124, 63)),
            R(148, 185, 0, 63, lg("208"), door=(160, 63)),
            R(185, 220, 0, 63, lg("209"), door=(195, 63)),
            R(220, 294, 0, 48, lg("210"), door=(232, 48)),
            R(240, 294, 48, 87, lg("211"), door=(240, 57)),
            R(240, 294, 87, 162, lg("212"), door=(240, 92)),
            R(207, 240, 108, 162, lg("213"), door=(232, 108)),
            R(188, 207, 108, 162, door=(198, 108)),  # 214 čajna kuhinja
            R(0, 50, 63, 100, lg("203"), door=(50, 70)),
            R(0, 57, 100, 162, lg("202"), door=(57, 110)),
            r201,
            R(147, 167, 120, 162, amenity=TOALET, door=(157, 120)),
            R(167, 188, 120, 162, amenity=TOALET, door=(178, 120)),
        ],
        **core(),
    }


def floor_3():
    r309 = R(238, 294, 0, 63, lg("309"), door=(238, 55))
    r309["poly"] = [(255, 0), (294, 0), (294, 63), (238, 63), (238, 48), (255, 48)]
    return {
        "title": "III sprat",
        "paths": [[(62, 81), (236, 81)], [(230, 81), (230, 52)], [(167, 81), (167, 118)], [(230, 81), (230, 110), (253, 110)],
                  [(87, 81), (87, 98)], [(198, 81), (198, 101)]],
        "corridors": [
            [(60, 63), (222, 63), (222, 48), (238, 48), (238, 100), (255, 100), (255, 118), (222, 118), (222, 101),
             (188, 101), (188, 120), (147, 120), (147, 107), (98, 107), (98, 100), (60, 100)],
        ],
        "rooms": [
            R(0, 73, 0, 15),  # terasa / ostava uz istočni zid (vrata iz 303)
            R(0, 73, 15, 63, lg("303"), door=(68, 63)),
            R(73, 105, 0, 63, lg("304"), door=(85, 63)),
            R(105, 133, 0, 63, lg("305"), door=(116, 63)),
            R(133, 193, 0, 63, lg("306"), door=(156, 63)),
            R(193, 222, 0, 63, lg("307"), door=(203, 63)),
            R(222, 255, 0, 48, lg("308"), door=(230, 48)),
            r309,
            R(238, 294, 63, 100, lg("310"), door=(238, 90)),
            R(255, 294, 100, 162, lg("311"), door=(255, 109)),
            R(207, 255, 118, 162, lg("312"), door=(232, 118)),
            R(188, 207, 101, 162, door=(198, 101)),  # 313 čajna kuhinja
            R(0, 60, 63, 100, lg("302"), door=(60, 90)),
            R(0, 98, 100, 162, lg("301"), door=(87, 100)),
            R(147, 167, 120, 162, amenity=TOALET, door=(157, 120)),
            R(167, 188, 120, 162, amenity=TOALET, door=(178, 120)),
        ],
        **core(),
    }


PLANS = {0: floor_0(), 1: floor_1(), 2: floor_2(), 3: floor_3()}
PLANS[0]["points"] = [("ULAZ", ENTRANCE, "ULAZ")]
for _plan in PLANS.values():
    _plan["corridors"].append(LIFT_FRONT)


def build():
    return build_graph(BUILDING, PLANS, stairs={"S": FLOORS}, lifts={"L": FLOORS})


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.parse_args()
    g = build()
    write_all(g, PLANS, VIEWPORT, WALL, "Departman za građevinarstvo i geodeziju", "build_dgg.py", "floor_plan_dgg", "DGG-0-ULAZ",
              CAMPUS_LINKS, source="fotografija evakuacionih planova (teren 10.11.2026)")


if __name__ == "__main__":
    main()
