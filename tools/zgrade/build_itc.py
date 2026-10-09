"""
ITC (Istraživačko-tehnološki centar): crteži prizemlja ... IV sprata i unutrašnji graf.

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/zgrade/build_itc.py

Pravi res/drawable/floor_plan_itc_{0..4}.xml i assets/itc.json (isti format kao nb.json).

Izvor: fotografije evakuacionih planova sva 5 nivoa (teren 09.10.2026, teren/izveštaj7/IMG_20261009_1105*..1110*, van gita).
Spoljni zid plana (0, 0, 344, 148) je OSM obris ITC-a (way 250277317) u zajedničkom sistemu (px plana NB, 1 px = M_PER_PX):
34,9 m zapad-istok x 15,0 m sever-jug. Zidovi sa fotografija su preneti srazmerno (spoljni zid fotografije -> WALL);
fotografije su malo iskošene, pregrade su tačne na ~0,5 m.

Orijentacija: gore sever, levo zapad, dole jug (strana ka Amfiteatrima). Tako vise planovi I-III sprata (jezgro sa liftom,
stepeništem i toaletima dole, uz južni zid) - kao F-blok, stepenište je dole. Plan prizemlja visi okrenut za 180° (glavni
izlaz dole na fotografiji = severni zid, ka OSM ulazu) i preračunat je. Zajednički sistem je ovaj zarotiran za 90° u smeru
kazaljke (to_shared), pa je rotacija smeštaja rotacija NB + 90° (build_campus.py, itc_placement).

Prizemlje: glavni ulaz na sredini severnog zida (portir u holu), dve učionice na istoku, hodnik sa 4 sobe na zapadu,
jezgro na jugu. Južno od zgrade je deo ka Amfiteatrima (u OSM-u spojni deo ITC-AMF): KLUB i uzan prolaz (na planu
"čajna kuhinja" je natpis pored njega) - prolaz do Amfiteatara ide tuda (PRETPOSTAVKA: na planu ITC-a se vidi samo
početak). I i II sprat: hodnik po sredini, 5 soba severno + 2 u istočnoj koloni, 2 sobe jugozapadno, jezgro, soba
jugoistočno. III: 4 sobe severno + tehnička (kos zid), AMFITEATAR na istoku (korisnik: "ITCA1 je veliki amfiteatar na
trećem spratu"), 3 sobe jugozapadno. IV: samo jezgro, učionica i mašinska prostorija lifta (lift ide do III), severno
terasa, ostalo krov (šrafirano).

Oznake sala NISU na planovima. U rasporedu: ITCA1 (amfiteatar III - korisnik), ITC03, ITC04, ITCS-01, ITCS-03, ITCS-RC.
Ostalo je PRETPOSTAVKA (teren 09.10.2026, korisnik: "ITC po pretpostavci"): ITC03 i ITC04 = dve učionice prizemlja
(natpis UČIONICA), ITCS-01 = velika soba I sprata (istočna kolona, sever), ITCS-03 = soba III sprata kod stepeništa,
ITCS-RC = učionica IV sprata (jedina sa natpisom UČIONICA gore).
"""

import argparse

from common import TOALET, R, build_graph, rect_poly, write_all

VX, VY, VW, VH = VIEWPORT = (-15, -30, 374, 257)
WALL = (0, 0, 344, 148)  # OSM obris (34,9 x 15,0 m)
BUILDING = "ITC"
FLOORS = [0, 1, 2, 3, 4]

# Jezgro (isto na svim spratovima; na fotografijama do ~0,3 m razlike).
LIFT_RECT = (122, 100, 140, 118)
STAIRS_RECT = (142, 100, 176, 148)
CORRIDOR = (62, 92)  # hodnik I-III (y)
ENTRANCE = (144, 0)  # glavni ulaz, prizemlje, sever
PASSAGE_AMF = (168, 212)  # kraj prolaza ka Amfiteatrima (južni zid spojnog dela)
CAMPUS_LINKS = [("K-U-ITC-1", "ITC-0-ULAZ"), ("K-P-ITC-AMF", "ITC-0-PROLAZ-AMF")]


def to_shared(p):
    """Tačka plana ITC-a -> zajednički sistem NB/AMF/Kule (desno sever, dole istok)."""
    return (1044 - p[1], p[0] - 149)


def core(floor, toilets=True):
    """Lift (P-III), stepenište, toaleti (dva, vrata ka holu) i ostava ispod lifta."""
    lifts = [("L", LIFT_RECT, (131, 99))] if floor <= 3 else []
    rooms = [R(122, 140, 118, 148)]
    if toilets:
        rooms += [R(178, 201, 101, 148, amenity=TOALET, door=(186, 101)),
                  R(201, 226, 101, 148, amenity=TOALET, door=(219, 101))]
    return {"lifts": lifts, "stairs": [("S", STAIRS_RECT, (159, 99))], "core_rooms": rooms}


def floor_0():
    c = core(0, toilets=False)
    return {
        "title": "Prizemlje",
        "outline": [rect_poly(*WALL), [(101, 148), (189, 148), (189, 161), (177, 161), (177, 212), (101, 212)]],
        "paths": [
            [(2, 75), (229, 75)],  # hodnik na zapadu i hol
            [(144, 2), (144, 75)],  # od glavnog ulaza
            [(159, 75), (159, 97)],  # ka stepeništu
            [(182, 75), (182, 155), (168, 155), (168, 210)],  # ka klubu i prolazu u Amfiteatre
        ],
        "corridors": [
            [(0, 61), (117, 61), (117, 0), (231, 0), (231, 89), (189, 89), (189, 161), (177, 161), (177, 212),
             (159, 212), (159, 148), (176, 148), (176, 100), (117, 100), (117, 89), (0, 89)],
        ],
        "entrances": [(128, -22, 160, 0), (159, 205, 177, 212)],
        "steps": [(128, -22, 160, -8)],  # spoljne stepenice glavnog ulaza
        "rooms": [
            # Zapad: hodnik sa po dve sobe.
            R(0, 73, 0, 61, door=(31, 61)), R(73, 117, 0, 61, door=(89, 61)),
            R(0, 59, 89, 148, door=(31, 89)), R(59, 117, 89, 148, door=(89, 89)),
            # Istok: dve učionice, vrata ka holu (PRETPOSTAVKA oznake).
            R(231, 344, 0, 75, "ITC03", door=(231, 66)), R(231, 344, 75, 148, "ITC04", door=(231, 84)),
            R(158, 189, 26, 56),  # portir
            *c["core_rooms"],
            R(189, 231, 103, 125, amenity=TOALET, door=(189, 114)),
            R(189, 231, 125, 148, amenity=TOALET, door=(189, 136)),
            R(101, 159, 148, 212, door=(159, 158)),  # klub
        ],
        "stairs": c["stairs"],
        "lifts": c["lifts"],
        "points": [("ULAZ", ENTRANCE, "ULAZ"), ("PROLAZ-AMF", PASSAGE_AMF, "PROLAZ")],
    }


def typical(floor):
    """I i II sprat (isti plan)."""
    c = core(floor)
    y0, y1 = CORRIDOR
    names = {1: {"ITCS-01": (285, 344, 0, 73)}}.get(floor, {})

    def room(x0, x1, ry0, ry1, door):
        name = next((n for n, r in names.items() if r == (x0, x1, ry0, ry1)), None)
        return R(x0, x1, ry0, ry1, name, door=door)

    return {
        "title": f"{'I' * floor} sprat",
        "paths": [[(2, 77), (283, 77)], [(159, 77), (159, 97)]],
        "corridors": [[(0, y0), (285, y0), (285, y1), (226, y1), (226, 101), (116, 101), (116, y1), (0, y1)]],
        "rooms": [
            *[room(a, b, 0, y0, (d, y0)) for a, b, d in ((0, 58, 27), (58, 116, 87), (116, 172, 157), (172, 230, 195),
                                                         (230, 285, 258))],
            room(285, 344, 0, 73, (285, 68)), room(285, 344, 75, 148, (285, 85)),
            room(0, 60, y1, 148, (32, y1)), room(60, 116, y1, 148, (89, y1)), room(226, 285, y1, 148, (258, y1)),
            *c["core_rooms"],
        ],
        "stairs": c["stairs"],
        "lifts": c["lifts"],
    }


def floor_3():
    c = core(3)
    y0, y1 = 61, CORRIDOR[1]
    amph = R(238, 344, 0, 148, "ITCA1", door=(238, 77))
    amph["poly"] = [(274, 0), (344, 0), (344, 148), (238, 148), (238, 59)]
    tech = R(232, 274, 0, 59)
    tech["poly"] = [(232, 0), (274, 0), (232, 59)]
    return {
        "title": "III sprat",
        "paths": [[(2, 77), (236, 77)], [(159, 77), (159, 97)]],
        "corridors": [[(0, y0), (232, y0), (238, 59), (238, y1), (226, y1), (226, 101), (116, 101), (116, y1), (0, y1)]],
        "rooms": [
            R(0, 60, 0, y0, door=(32, y0)), R(60, 114, 0, y0, door=(89, y0)),
            R(114, 174, 0, y0, "ITCS-03", door=(150, y0)), R(174, 232, 0, y0, door=(186, y0)),
            tech, amph,
            R(0, 60, y1, 148, door=(31, y1)), R(60, 90, y1, 148, door=(86, y1)), R(90, 116, y1, 148, door=(94, y1)),
            R(226, 238, y1, 148),
            *c["core_rooms"],
        ],
        "stairs": c["stairs"],
        "lifts": c["lifts"],
    }


def floor_4():
    c = core(4, toilets=False)
    return {
        "title": "IV sprat",
        "roof": rect_poly(*WALL),
        "outline": [[(112, 54), (237, 54), (237, 148), (112, 148)]],
        "voids": [(0, 0, 112, 148), (237, 0, 344, 148)],  # krov (šrafirano na planu)
        "paths": [[(159, 97), (159, 77), (179, 77)]],
        "corridors": [[(142, 54), (181, 54), (181, 100), (142, 100)]],
        "rooms": [
            R(112, 142, 58, 95),  # mašinska prostorija lifta
            R(181, 237, 54, 144, "ITCS-RC", door=(181, 80)),
            R(112, 123, 125, 148),  # tehnička
            R(112, 142, 95, 125),
        ],
        "stairs": c["stairs"],
    }


PLANS = {0: floor_0(), 1: typical(1), 2: typical(2), 3: floor_3(), 4: floor_4()}


def build():
    return build_graph(BUILDING, PLANS, stairs={"S": FLOORS}, lifts={"L": [0, 1, 2, 3]})


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.parse_args()
    g = build()
    write_all(g, PLANS, VIEWPORT, WALL, "ITC", "build_itc.py", "floor_plan_itc", "ITC-0-ULAZ", CAMPUS_LINKS,
              source="fotografija evakuacionih planova (teren 09.10.2026)")


if __name__ == "__main__":
    main()
