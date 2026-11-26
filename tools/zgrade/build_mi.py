"""
Mašinski institut (MI, "Instituti Mašinskog odseka"): crteži prizemlja i I sprata i unutrašnji graf.

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/zgrade/build_mi.py

Pravi res/drawable/floor_plan_mi_{0,1}.xml i assets/mi.json (isti format kao nb.json).

Od 25.11.2026 zidovi su iz mi_geometry.py (izmereno zid po zid sa evakuacionih planova, teren 03.10.2026; plan sprata visi
okrenut za 180°), a oznake sala sa stranice "MI prostorije" - korisnik ih je obeležio na licu mesta (16., 17. i 25.11.2026;
sve sale iz rasporeda osim 125, koju nije našao). Ovde su oznake (LABELS), vrata (DOORS, lukovi otvaranja sa istih planova),
mreža puteva pod pravim uglom i stepeništa. Stari crtež (OSM obris + pretpostavljene oznake redom po krilu) je u git istoriji.

Oblik: dugački deo (sever-jug) sa hodnikom po sredini i glavnim ulazom na istoku (portirnica), tri krila ka zapadu sa hodnikom
po sredini: A | B (jug), V | G, D | Đ (sever). Na I spratu su delovi krila hale bez poda (VOIDS_1). Do soba na spratu u krilima
V, G, D i Đ stiže se samo svojim stepeništem iz prizemlja; do krila A i B stepenicama u hodniku A|B (korisnik).

Predvorje glavnog ulaza (do y -507) je iz OSM-a i sa table iz 2008 - evakuacioni plan ga ne crta.
"""

import argparse

import mi_geometry as m
from common import TOALET, Flights, R, build_graph, rect_poly, write_all

VX, VY, VW, VH = VIEWPORT = (-150, -1140, 1180, 650)
BUILDING = "MI"
FLOORS = [0, 1]
WALL = (-135, -1124, 1014, -507)
PORCH = rect_poly(397, -576, 471, -507)  # predvorje glavnog ulaza (OSM)
ENTRANCE = (434, -508)
CAMPUS_LINKS = [("K-U-MI-1", "MI-0-ULAZ")]

ROOMS = {0: m.ROOMS_0, 1: m.ROOMS_1}

# Oznake (bez "MI "): indeks sobe u mi_geometry.ROOMS_f -> oznaka. Stranica "MI prostorije" (korisnik, na licu mesta).
MARKED = {
    0: {33: "B1", 34: "B1-A", 35: "B1-B", 38: "SD1", 44: "24-A", 47: "D0", 24: "16",
        55: "A3",  # "Ulaz do više A3 prostorija"
        70: "V4", 71: "V4-4", 72: "V4-5", 73: "V4-6", 75: "V3-1",
        79: "G3-1", 80: "G3-2", 83: "G2", 102: "G2-2",
        89: "D5", 90: "D4", 91: "D3", 93: "D4-D",
        92: "D2",  # po tabli iz 2008, korisnik potvrdio (25.11.2026: "Upiši D2")
        96: "Đ3-2", 97: "Đ2-2", 98: "Đ2", 100: "A20"},
    1: {61: "125C", 62: "125B", 63: "125A",
        67: "A2-1", 68: "A2-2", 69: "A2-3", 70: "A2-4", 71: "A2-4A", 72: "A3Gal./2", 74: "A2-0",
        75: "B4-1", 76: "B4-1A", 77: "B4-2", 78: "B4-B", 79: "B4-A", 80: "B4-3", 81: "B4-4", 82: "B4-4A", 83: "B4-5", 84: "B4-5A",
        85: "V4-2", 86: "V4-1", 112: "V4-3", 87: "V3-5", 88: "V3-6",
        89: "G3-1C", 90: "G3-1B", 91: "G3-1A", 92: "G3-1K", 93: "G2-3", 94: "G2-1", 113: "G2-4",
        96: "D5-Gal.", 98: "D4-B", 99: "D4-C", 100: "D4-A",
        104: "Đ5-1", 105: "Đ4-2", 106: "Đ3-4", 107: "Đ3-3", 108: "Đ4-1", 109: "Đ3-1", 110: "Đ2-1", 111: "Đ1-1"},
}
# Brojevi soba dugačkog dela sa table na ulazu iz 2008 (teren/izveštaj3/IMG_20261003_115758.jpg) - OTPRILIKE (korisnik,
# 25.11.2026: "Možeš i otprilike popuniti ... u njima nema redovne nastave"). Tabla je u razmeri: 2,87 px isečka po px plana,
# sidra V|G = 357, južni kraj hodnika = -81, kraj reda B1 = 62,5; na spratu i 111|112 = 517, 112|113 = 617 (jezgro S2). Od 2008. su
# neke sobe podeljene - tada broj nosi soba koja ga najviše pokriva, ostale su bez oznake (npr. 109 = 13, 14 i 15 sa vratima između).
# Prizemlje desno od ulaza: devet soba i devet oznaka sa spiska na tabli (11-12, 13a, 13b, 13g, 14, 14a, 15, 15a) do učionice 16.
BOARD = {
    0: {0: "1", 3: "2", 4: "3", 5: "4", 6: "5", 7: "6", 8: "6a", 9: "7", 10: "8", 11: "9", 12: "10",
        15: "11", 16: "12", 17: "13a", 18: "13b", 19: "13g", 20: "14", 21: "14a", 22: "15", 23: "15a",
        25: "16a", 26: "17", 27: "18", 28: "19"},
    1: {1: "101", 3: "102", 6: "103", 7: "104", 9: "105", 10: "106", 11: "107", 12: "108", 14: "109", 16: "110", 18: "111", 20: "112",
        22: "113", 23: "114", 24: "115", 25: "116", 26: "117", 27: "118", 28: "119", 29: "120", 30: "121", 31: "122", 32: "123",
        66: "124", 45: "126", 59: "129", 35: "131", 34: "132", 36: "133", 37: "134", 38: "135", 51: "136", 50: "137", 52: "138",
        53: "139", 54: "140"},
}
LABELS = {f: {**MARKED[f], **BOARD[f]} for f in FLOORS}

# Vrata soba sa oznakom i toaleta (indeks -> tačke), sa evakuacionih planova.
SOUTH_0, NORTH_0 = -631.5, -659  # zidovi hodnika dugačkog dela, prizemlje
SOUTH_1, NORTH_1 = -632.5, -660
DOORS = {
    0: {
        0: [(-81, -645)],
        **{i: [(x, SOUTH_0)] for i, x in {3: -59, 4: -24, 5: 48, 6: 79, 7: 106, 8: 178, 9: 210, 10: 239, 15: 487, 16: 538, 17: 569,
                                          18: 601, 19: 634, 20: 668, 21: 701, 22: 732, 23: 767, 24: 830, 25: 862, 27: 952}.items()},
        11: [(290.75, -617)], 12: [(357, -617)],  # kroz sobu 13 (vrata na hodnik) i sobu 12
        26: [(910, -622)], 28: [(975, -622)],  # iz malog hola, kroz sobu 27
        **{i: [(x, NORTH_0)] for i, x in {33: 57, 34: 105, 35: 117, 38: 350, 44: 532, 47: 808}.items()},
        31: [(-35, -709)], 36: [(170, NORTH_0), (185, NORTH_0)], 40: [(422.5, -701)], 46: [(690, NORTH_0), (707, NORTH_0)],
        48: [(879, -699)],
        55: [(-34, -811)],
        70: [(380, -957.5)], 71: [(375, -942)], 72: [(375, -932)], 73: [(396, -924)], 75: [(400, -777)],
        79: [(464, -955)], 80: [(500, -955)], 102: [(463, -904)], 83: [(456, -777)],
        89: [(881, -1003.5)], 90: [(881, -851)], 91: [(857, -795)], 92: [(881, -777)], 93: [(781, -851)],
        96: [(914, -1038)], 97: [(914, -934)], 98: [(914, -875)], 100: [(914, -786)],
    },
    1: {
        1: [(-66.25, -627)], 3: [(-49, -621)],
        **{i: [(x, SOUTH_1)] for i, x in {6: 51, 7: 82, 9: 148, 10: 179, 11: 212, 12: 273, 14: 373, 16: 435, 18: 504, 20: 567, 22: 632,
                                          23: 667, 24: 701, 25: 732, 26: 767, 27: 798, 28: 831, 29: 863, 30: 920, 31: 952,
                                          32: 985}.items()},
        **{i: [(x, NORTH_1)] for i, x in {45: 250, 59: 630, 61: 740, 62: 755, 63: 788}.items()},
        44: [(172, NORTH_1), (186, NORTH_1)], 60: [(688, NORTH_1), (708, NORTH_1)],
        34: [(-42, -694.5)], 35: [(-51, -672)], 36: [(-1, -686)], 37: [(8, -694.5)], 38: [(16, -675)],
        50: [(411, -694)], 51: [(403, -670)], 52: [(454, -683)], 53: [(462, -695)], 54: [(471, -676)], 66: [(911, -667)],
        67: [(-31, -1048)], 68: [(-31, -1033)], 69: [(-31, -900)], 70: [(-31, -886)], 71: [(-31, -853)],
        72: [(-67.5, -788)], 74: [(-6, -1074)],
        75: [(8, -1087.5)], 76: [(17.5, -1080)], 77: [(46, -956)], 78: [(36, -935.5)], 79: [(56, -950)], 80: [(46, -924)],
        81: [(1, -836)], 82: [(1, -804)], 83: [(1, -787)], 84: [(1, -757)],
        85: [(368, -930)], 86: [(383, -924)], 112: [(405, -930)], 87: [(386, -755)], 88: [(393, -762.5)],
        89: [(465, -1054)], 90: [(475, -1030)], 91: [(475, -962)], 92: [(500, -951)], 93: [(502, -897)], 94: [(495, -891)],
        113: [(489, -897)],
        96: [(838, -1055)], 98: [(828, -899)], 99: [(845, -899)], 100: [(837, -891)],
        104: [(960.5, -1055)], 105: [(951, -997)], 106: [(951, -963)], 107: [(951, -951)], 108: [(969, -997)], 109: [(969, -984)],
        110: [(960.5, -924)], 111: [(913, -787)],
    },
}

# Čvor sobe nepravilnog oblika: pravougaonik u kom je (inače sredina celog oblika).
NODE_RECT = {(0, 0): (-134, -81, -659, -623), (0, 79): (456, 552.5, -1121, -1021), (0, 75): (329, 400, -796, -730),
             (0, 100): (914, 1012, -794, -764), (1, 99): (845, 881, -907.5, -891), (1, 111): (913, 1010, -791, -764)}

BAR = -645  # srednja linija hodnika dugačkog dela (oba sprata)
# Mreža puteva (pravi uglovi). Grana počinje na liniji koja je u spisku PRE nje (common.Network spaja krajeve samo tada).
PATHS = {
    0: [
        [(-81, BAR), (952, BAR)],
        # Predvorje, pored portirnice. Završava se pre ulaza: ulaz se kači na kraj ivicom ka vratima. Do 26.11.2026 je putanja išla do
        # same tačke ulaza - čvor hodnika na istom mestu kao ULAZ (ivica dužine 0), pa izlazak kroz spoljna vrata nije znao pravac kroz zid
        # (teren 26.11.2026: "Zaglavila se tačka, neće da izađe").
        [(405, BAR), (405, -540), (434, -540)],
        [(378, BAR), (378, -617), (290.75, -617)],  # kroz sobe 13 i 12 do 11
        [(895, BAR), (895, -622), (910, -622)],  # mali hol južnog reda
        [(952, BAR), (952, -622), (975, -622)],  # istočni hol, kroz sobu 27 do 28
        [(-17, BAR), (-17, -755), (-8.5, -755), (-8.5, -931)],  # hodnik A|B, iza požarnih vrata pored stepenica
        [(-17, -755), (-23.5, -755)],
        [(-8.5, -811), (-61, -811), (-61, -800)],  # A3 (tehnički pregled) do stepeništa SAB
        [(438.5, BAR), (438.5, -985)],  # hodnik V|G
        [(380, -928), (380, -950)],  # predvorje SV
        [(438.5, -942), (413, -942), (413, -934), (380, -934)],
        [(438.5, -777), (407, -777), (407, -768)],  # predvorje SV2
        [(438.5, -940), (464, -940), (464, -950), (500, -950)],  # predvorje SG
        [(438.5, -918), (463, -918), (463, -910)],  # predvorje SG2
        [(897.5, BAR), (897.5, -1048)],  # hodnik D|Đ
        [(897.5, -1003.5), (868, -1003.5), (868, -1008)],  # kroz D5 do stepeništa SD1
        [(897.5, -919), (872, -919)],  # predvorje SD2
        [(897.5, -851), (790, -851)],  # kroz D4 do D4-D
        [(897.5, -777), (857, -777), (857, -790)],  # kroz D2 do D3
    ],
    1: [
        [(-49, BAR), (985, BAR)],
        [(-49, BAR), (-49, -627)],  # hol na južnom kraju
        [(-42, BAR), (-42, -690)], [(8, BAR), (8, -690)], [(411, BAR), (411, -690)], [(462, BAR), (462, -690)],  # mali holovi
        [(905, BAR), (905, -723), (894, -723), (894, -1080)],  # hodnik D|Đ
        # Hodnik A|B: odvojen od dugačkog dela (vrata kroz sobu 133 postoje na planu, ali korisnik u krilo ide stepenicama).
        [(-16, -1066), (-16, -840), (-8.5, -840), (-8.5, -750)],
        [(-16, -840), (-23.5, -840)],
        [(-16, -1066), (8, -1066), (8, -1080)],
        [(-16, -951), (46, -951), (46, -930)],  # mini hodnik ka B4-2, B4-3, B4-A, B4-B (tri stepenika)
        [(-61, -785), (-61, -791)],  # podest SAB
        [(372, -930), (400, -930)],  # predvorje SV
        [(393, -752), (393, -758)],  # podest SV2
        [(465, -1048), (465, -931)], [(465, -951), (496, -951)],  # hodnik krila G i predvorje SG
        [(495, -900), (495, -894)],  # hol SG2
        [(850, -1048.5), (790, -1048.5)], [(837, -904), (837, -894)], [(828, -819), (828, -803)],  # SD1, SD2, SD3
        [(960.5, -1048), (960.5, -930)],  # hodnik krila Đ
    ],
}

# Stepenište: čvor na prizemlju (dno) i na spratu (gde se stiže) - po strelicama i gazištima na planovima.
STAIR_NODES = {
    "S1": ((202, -664), (218, -664)), "S2": ((674, -664), (658, -664)),
    "SAB": ((-61, -794), (-57.5, -788)),
    "SV": ((413, -950), (381, -936)), "SV2": ((407, -762), (400, -755)),
    "SG": ((469, -940), (467.5, -931)), "SG2": ((469, -918), (495, -903)),
    "SD1": ((868, -1012), (855, -1048.5)), "SD2": ((862, -919), (837, -907.5)), "SD3": ((879, -819), (836, -819)),
    "SĐ": ((914, -981), (951, -982)),
}
# Hodnik A|B: pravo stepenište (kao AMF S2) - dva kraka u nizu sa podestom, sprat se menja pređenim putem.
AB_PATH = [(-23.5, -763), (-23.5, -786), (-23.5, -803), (-23.5, -827)]
RECTS = {0: m.STAIRS_0, 1: m.STAIRS_1}


def bounds(shape):
    xs, ys = [x for x, _ in shape], [y for _, y in shape]
    return min(xs), max(xs), min(ys), max(ys)


def rooms(f):
    out = []
    for i, (shape, _, kind) in enumerate(ROOMS[f]):
        label = LABELS[f].get(i)
        x0, x1, y0, y1 = bounds(shape)
        room = R(*NODE_RECT.get((f, i), (x0, x1, y0, y1)), f"MI {label}" if label else None, doors=DOORS[f].get(i),
                 amenity=TOALET if kind == "WC" else None)
        assert bool(label or kind == "WC") == (i in DOORS[f]), (f, i, label)
        if (f, i) in NODE_RECT or shape != m.rect(x0, x1, y0, y1):
            room["poly"] = shape
        out.append(room)
    for a, b in getattr(m, f"WALLS_{f}"):  # zid bez sobe (samo crtež)
        out.append({**R(a[0], b[0], a[1], b[1]), "poly": [a, b]})
    return out


def floor_plan(f):
    stairs = [(key, rects[0], STAIR_NODES[key][f]) for key, rects in RECTS[f].items()] + [("AB", None, AB_PATH[0])]
    steps = [r for rects in RECTS[f].values() for r in rects[1:]]
    corridors = list(m.CORRIDORS_0 if f == 0 else m.CORRIDORS_1)
    return {
        "title": "Prizemlje" if f == 0 else "I sprat",
        "outline": [m.OUTLINE_0, PORCH] + m.ANNEXES_0 if f == 0 else [m.OUTLINE_1],
        "corridors": corridors + ([PORCH] if f == 0 else []),
        "paths": PATHS[f],
        "rooms": rooms(f),
        "stairs": stairs,
        "flights": {"AB": Flights(up=m.AB_LOWER, down=m.AB_UPPER, landing=m.AB_LANDING, path=AB_PATH, draw_down=f == 1)},
        "points": [("ULAZ", ENTRANCE, "ULAZ")] if f == 0 else [],
        "voids": [] if f == 0 else m.VOIDS_1,
        "steps": steps + ([m.MINI_STEPS_B_1] if f == 1 else []),
    }


PLANS = {f: floor_plan(f) for f in FLOORS}


def build():
    labels = [label for f in FLOORS for label in LABELS[f].values()]
    assert len(labels) == len(set(labels)) == sum(len(MARKED[f]) + len(BOARD[f]) for f in FLOORS)
    return build_graph(BUILDING, PLANS, stairs={key: FLOORS for key in [*STAIR_NODES, "AB"]}, lifts={})


def main():
    argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter).parse_args()
    write_all(build(), PLANS, VIEWPORT, WALL, "Mašinski institut", "build_mi.py", "floor_plan_mi", "MI-0-ULAZ", CAMPUS_LINKS,
              source="fotografija evakuacionih planova (teren 03.10.2026)")


if __name__ == "__main__":
    main()
