"""
Mašinski institut (MI, "Instituti Mašinskog odseka"): crteži prizemlja i I sprata i unutrašnji graf.

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/zgrade/build_mi.py

Pravi res/drawable/floor_plan_mi_{0,1}.xml i assets/mi.json (isti format kao nb.json).

Izvor: fotografije evakuacionih planova prizemlja i I sprata (teren 03.10.2026, teren/izveštaj3/IMG_20261003_115627.jpg
i _115422.jpg, van gita; plan sprata visi okrenut za 180°). Perspektiva je ispravljena homografijom po 4 spoljna ugla
zgrade (dva ugla dugačkog dela uz ulaz, krajnji uglovi krila A i Đ) na OSM obris u zajedničkom sistemu (px plana NB,
desno sever, dole istok) - MI je skoro tačno poravnat sa NB (dugački deo -113,9° naspram -114,03°). Pregrade su
izmerene automatski (tamne linije koje preseku više linija skeniranja unutar reda soba) i ručno pregledane.

Oblik: dugački deo (sever-jug) sa hodnikom po sredini i glavnim ulazom na istoku (portirnica), tri krila ka zapadu sa
hodnikom po sredini: A | B (jug), V | G, D | Đ (sever). Između krila su jezgra sa stepeništem (evakuacioni izlazi).
Uz krilo D je dogradnja (dve učionice, magacin) koje nema u OSM obrisu. Na I spratu su delovi krila šrafirani - visoke
hale bez poda (VOIDS); delovi V i G na spratu se dostižu samo svojim stepeništem iz prizemlja.

Sale: evakuacioni planovi nemaju brojeve. Oznaka je krilo + broj bloka + broj sobe (liste na ulazu u lamele A i B,
tabla iz 2008): sprat iz liste za A i B, inače "-0" / bez nastavka = prizemlje, "-n" (n >= 1) i galerije = I sprat.
Sale krila se raspoređuju po redosledu oznake od dugačkog dela ka kraju krila, po sobama tog krila na tom spratu -
PRETPOSTAVKA (pravo mesto unutar krila nije poznato, krilo i sprat jesu). MI 16 (učionica) i MI 125 su sa table iz
2008. MI 24-A i MI SD1 nisu nađeni (ruta do zgrade).
"""

import argparse

from common import R, build_graph, write_all

VX, VY, VW, VH = VIEWPORT = (-150, -1140, 1180, 650)
BUILDING = "MI"
FLOORS = [0, 1]

# OSM obris (way 222832768) u zajedničkom sistemu, zaokružen.
OUTLINE = [(398, -576), (-115, -576), (-135, -577), (-136, -601), (-134, -664), (-99, -663), (-99, -729), (-135, -729),
           (-132, -1124), (102, -1124), (100, -742), (168, -742), (168, -721), (253, -721), (254, -743), (328, -742),
           (330, -1124), (563, -1123), (560, -742), (621, -741), (620, -721), (717, -721), (718, -741), (788, -741),
           (790, -1124), (1014, -1123), (1012, -724), (979, -725), (978, -664), (1012, -664), (1011, -576), (471, -576),
           (471, -507), (397, -507)]
WALL = (-135, -1124, 1014, -507)
ANNEX = [(712, -955), (780, -955), (780, -742), (712, -742)]  # dogradnja uz krilo D (prizemlje)

BAR_Y = (-659, -632)  # hodnik dugačkog dela
SOUTH_Y = (-632, -577)  # red soba uz istočni zid
NORTH_Y = (-742, -659)  # red soba prema krilima
WING_END = -1121
WINGS = {  # krilo: (x0, x1) kolone soba
    "A": (-132, -33), "B": (-2, 97), "V": (325, 424), "G": (455, 552), "D": (780, 880), "Đ": (912, 1010),
}
WING_CORRIDORS = {"AB": (-33, -2), "VG": (424, 455), "DĐ": (880, 911)}
ENTRANCE = (434, -508)
CAMPUS_LINKS = [("K-U-MI-1", "MI-0-ULAZ")]

# Pregrade (px plana), izmerene na ispravljenim fotografijama.
SOUTH_WALLS = {
    0: [-133, -101, -68, -36, -3, 62, 94, 127, 192, 224, 256, 290, 357, 388, 454, 519, 552, 584, 618, 649, 681, 713,
        746, 779, 845, 878, 911, 943, 975, 1008],
    1: [-133, -68, -35, -2, 31, 63, 96, 129, 163, 194, 226, 323, 357, 389, 421, 453, 485, 518, 551, 584, 617, 650,
        681, 714, 747, 779, 812, 845, 878, 944, 976, 1009],
}
ENTRANCE_PASSAGE = (388, 454)  # u južnom redu prizemlja: prolaz od ulaza do hodnika (i portirnica)
NORTH_ROOMS = {  # (x0, x1); hodnici krila i jezgra su izostavljeni
    0: [(-133, -98), (-98, -57), (-57, -35), (-2, 63), (63, 112), (112, 162), (257, 357), (357, 379), (379, 422),
        (455, 519), (519, 618), (714, 813), (813, 858), (858, 879), (911, 976), (976, 1008)],
    1: [(-133, -99), (-99, -52), (-52, -33), (-2, 64), (64, 130), (130, 163), (258, 291), (291, 324), (324, 355),
        (355, 403), (403, 420), (453, 519), (519, 585), (585, 618), (714, 747), (747, 780), (780, 813), (813, 879),
        (911, 977), (977, 1009)],
}
CORES = [(162, 257), (618, 714)]  # jezgra sa stepeništem između krila (severni red)
WING_WALLS = {  # krilo -> sprat -> pregrade duž krila (y), od kraja krila ka dugačkom delu
    "A": {0: [-1121, -1088, -990, -828, -795, -742], 1: [-1121, -1039, -991, -893, -861, -828, -808, -742]},
    "B": {0: [-1121, -1023, -991, -937, -893, -778, -742], 1: [-1121, -1087, -1056, -957, -923, -860, -828, -795, -763, -742]},
    "V": {0: [-1121, -957, -937, -860, -796, -742], 1: [-957, -925, -860, -800, -756, -742]},
    "G": {0: [-1121, -944, -860, -742], 1: [-1121, -1053, -1022, -956, -925, -891, -845]},
    "D": {0: [-1121, -1085, -1054, -924, -907, -826, -794, -742], 1: [-1121, -1056, -924, -891, -827, -793, -765, -742]},
    "Đ": {0: [-1121, -1054, -989, -942, -924, -891, -859, -794, -762, -742], 1: [-1121, -1055, -990, -924, -858, -815, -793, -742]},
}
ANNEX_WALLS = [-955, -885, -790, -742]
# I sprat: šrafirano na planu - visoke hale bez poda na spratu (i spoljne stepenice krila A).
VOIDS = {
    "V": [(-1121, -957), (-860, -800)], "G": [(-845, -760)], "D": [(-1056, -924), (-765, -742)], "Đ": [(-858, -815)],
    "A": [(-828, -808)],
}

# Stepeništa između prizemlja i I sprata: ključ -> (pravougaonik na prizemlju, na spratu); čvor je u sredini.
STAIRS = {
    "S1": ((198, -705, 222, -662), (198, -705, 222, -662)),  # jezgro A/B - V/G
    "S2": ((660, -705, 684, -662), (660, -705, 684, -662)),  # jezgro V/G - D/Đ
    "SAB": ((-58, -800, -38, -778), (-75, -808, -50, -788)),
    "SV": ((372, -960, 402, -935), (372, -960, 402, -935)),
    "SV2": ((385, -790, 410, -745), (385, -790, 410, -745)),
    "SG": ((462, -945, 495, -905), (462, -945, 495, -905)),
    "SD1": ((852, -1060, 874, -1035), (850, -1060, 872, -1035)),
    "SD2": ((828, -925, 852, -905), (845, -918, 870, -898)),
    "SD3": ((840, -832, 865, -812), (832, -832, 860, -810)),
    "SĐ": ((915, -995, 950, -978), (918, -1000, 950, -983)),
}

# Sale: krilo -> sprat -> oznake (bez "MI "). Lista A i B sa ulaza u lamele + raspored (assets/schedule.json).
WING_ROOMS = {
    "A": {0: ["A1", "A1-1", "A3-1", "A3-2", "A3-5", "A4"],
          1: ["A2-0", "A2-1", "A2-2", "A2-3", "A2-4", "A2-4A", "A3-3", "A3-4", "A3Gal./2"]},
    "B": {0: ["B1", "B1-A", "B1-B", "B2", "B3", "B4-0A", "B4-0B", "B4-0C", "B4-0D", "B5"],
          1: ["B4-1", "B4-1A", "B4-2", "B4-A", "B4-B", "B4-3", "B4-4", "B4-4A", "B4-5", "B4-5A"]},
    "V": {1: ["V3-5", "V4-1", "V4-2", "V4-4"]},
    "G": {0: ["G2"], 1: ["G2-1", "G2-2", "G3-1A", "G3-1C"]},
    "D": {0: ["D0", "D3", "D4", "D4-A", "D4-D", "D5"], 1: ["D5-Gal."]},
    "Đ": {0: ["Đ2"], 1: ["Đ1-1", "Đ2-1", "Đ3-1", "Đ3-2", "Đ4-1", "Đ4-2", "Đ5-1"]},
}
BAR_ROOMS = {0: {"16": ("south", 790)}, 1: {"125": ("north", 848)}}  # tabla iz 2008: soba koja sadrži x


def in_voids(wing, y0, y1, floor):
    return floor == 1 and any(a <= y0 and y1 <= b for a, b in VOIDS.get(wing, []))


# I sprat: hodnik unutar krila (x0, x1, y0, y1) - sobe se ne crtaju preko njega (G: uz levi zid, Đ: po sredini).
SUB_CORRIDORS = {"G": (455, 470, -1085, -848), "Đ": (950, 965, -1055, -924)}
# I sprat, krilo V: dva ostrva (svako sa svojim stepeništem iz prizemlja) - sobe zadate direktno.
V_ISLANDS = [(325, 367, -925, -860), (367, 424, -925, -860), (325, 383, -800, -742)]
V_LOBBY = (335, -934, 415, -925)  # predvorje ispod stepeništa SV, ka sobama srednjeg ostrva


def wing_room_rects(wing, floor):
    """Sobe krila (pravougaonici x0, x1, y0, y1) od dugačkog dela ka kraju krila, bez praznina, uskih traka i
    hodnika u krilu."""
    if wing == "V" and floor == 1:
        return sorted(V_ISLANDS, key=lambda r: (-r[3], r[0]))
    walls = WING_WALLS[wing].get(floor, [])
    x0, x1 = WINGS[wing]
    rects = [(x0, x1, a, b) for a, b in zip(walls, walls[1:]) if b - a >= 15 and not in_voids(wing, a, b, floor)]
    sub = SUB_CORRIDORS.get(wing) if floor == 1 else None
    if sub:
        cx0, cx1, cy0, cy1 = sub
        split = []
        for r in rects:
            if r[2] < cy1 and r[3] > cy0:  # preklapa se sa hodnikom - sobe levo i desno od njega
                split += [p for p in ((r[0], cx0, r[2], r[3]), (cx1, r[1], r[2], r[3])) if p[1] - p[0] >= 12]
            else:
                split.append(r)
        rects = split
    return sorted(rects, key=lambda r: (-r[3], r[0]))


def wing_rooms(wing, floor):
    """Sobe krila: sale redom od dugačkog dela po sobama; kad je oznaka više nego soba, soba se deli po dužini
    krila (nacrtano - svaka sala u grafu je i soba na crtežu; pregrade su tada PRETPOSTAVKA)."""
    names = WING_ROOMS.get(wing, {}).get(floor, [])
    rects = wing_room_rects(wing, floor)
    groups = [[] for _ in rects]
    for i, name in enumerate(names):
        groups[min(len(rects) - 1, i * len(rects) // len(names))].append(name)
    out = []
    for (x0, x1, y0, y1), group in zip(rects, groups):
        if not group:
            out.append(R(x0, x1, y0, y1))
        for j, name in enumerate(group):
            a = y1 - (y1 - y0) * (j + 1) / len(group)
            b = y1 - (y1 - y0) * j / len(group)
            out.append(R(x0, x1, round(a, 1), round(b, 1), f"MI {name}"))
    return out


def bar_rooms(floor):
    walls = SOUTH_WALLS[floor]
    south = [(a, b) for a, b in zip(walls, walls[1:]) if not (floor == 0 and ENTRANCE_PASSAGE[0] <= a < ENTRANCE_PASSAGE[1])]
    named = {(side, next((a, b) for a, b in (south if side == "south" else NORTH_ROOMS[floor]) if a <= x < b)): label
             for label, (side, x) in BAR_ROOMS.get(floor, {}).items()}

    def room(side, a, b):
        label = named.get((side, (a, b)))
        return R(a, b, *(SOUTH_Y if side == "south" else NORTH_Y), f"MI {label}" if label else None)

    rooms = [room("south", a, b) for a, b in south] + [room("north", a, b) for a, b in NORTH_ROOMS[floor]]
    if floor == 0:
        rooms.append(R(421, ENTRANCE_PASSAGE[1], *SOUTH_Y))  # portirnica (uz prolaz od ulaza)
    return rooms


def floor_plan(floor):
    rooms = bar_rooms(floor)
    for wing in WINGS:
        rooms += wing_rooms(wing, floor)
    rooms += [R(x0, x1, y0, y1) for x0, x1, y0, y1 in
              [(c0, c1, NORTH_Y[0], NORTH_Y[1]) for c0, c1 in CORES]]
    x_bar = (-120, 995)
    y_bar = sum(BAR_Y) / 2
    corridors = [[(-133, BAR_Y[0]), (1008, BAR_Y[0]), (1008, BAR_Y[1]), (-133, BAR_Y[1])]]
    paths = [[(x_bar[0], y_bar), (x_bar[1], y_bar)]]
    if floor == 0:
        outline = [OUTLINE, ANNEX]
        rooms += [R(712, 780, a, b) for a, b in zip(ANNEX_WALLS, ANNEX_WALLS[1:])]
        for key, (x0, x1) in WING_CORRIDORS.items():
            corridors.append([(x0, WING_END), (x1, WING_END), (x1, BAR_Y[0]), (x0, BAR_Y[0])])
            paths.append([((x0 + x1) / 2, y_bar), ((x0 + x1) / 2, -1110)])
        corridors.append([(ENTRANCE_PASSAGE[0], BAR_Y[1]), (421, BAR_Y[1]), (421, -577), (ENTRANCE_PASSAGE[0], -577)])
        corridors.append([(397, -577), (471, -577), (471, -507), (397, -507)])  # predvorje (portirnica desno)
        x_passage = sum(ENTRANCE_PASSAGE) / 2 - 16  # sredina prolaza (388-421), portirnica je desno
        paths.append([(x_passage, y_bar), (x_passage, -540), ENTRANCE])
        points = [("ULAZ", ENTRANCE, "ULAZ")]
        voids = []
    else:
        outline = [OUTLINE[:31]]  # bez predvorja (prizemno)
        for key in ("AB", "DĐ"):
            x0, x1 = WING_CORRIDORS[key]
            corridors.append([(x0, -1110), (x1, -1110), (x1, BAR_Y[0]), (x0, BAR_Y[0])])
            paths.append([((x0 + x1) / 2, y_bar), ((x0 + x1) / 2, -1105)])
        # Hodnici u krilima Đ (po sredini, uz stepenište SĐ) i G (uz levi zid); V: predvorje srednjeg ostrva. G i V su
        # ostrva do kojih se stiže svojim stepeništem iz prizemlja.
        for x0, x1, y0, y1 in SUB_CORRIDORS.values():
            corridors.append([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])
            paths.append([((x0 + x1) / 2, y0 + 5), ((x0 + x1) / 2, y1 - 5)])
        lx0, ly0, lx1, ly1 = V_LOBBY
        corridors.append([(lx0, ly0), (lx1, ly0), (lx1, ly1), (lx0, ly1)])
        # spojnica hodnika D|Đ i hodnika u Đ - krajevi tačno na srednjim linijama (common.Network spaja samo tada)
        mid = lambda c: sum(c[:2]) / 2
        paths += [[(mid(WING_CORRIDORS["DĐ"]), -990), (mid(SUB_CORRIDORS["Đ"]), -990)],
                  [(lx0 + 5, (ly0 + ly1) / 2), (lx1 - 5, (ly0 + ly1) / 2)],
                  [(383, -772), (397, -772)]]
        points = []
        voids = [(x0, a, x1, b) for wing, spans in VOIDS.items() for a, b in spans for x0, x1 in [WINGS[wing]]]
    stairs = [(key, rects[floor], ((rects[floor][0] + rects[floor][2]) / 2, (rects[floor][1] + rects[floor][3]) / 2))
              for key, rects in STAIRS.items()]
    return {
        "title": "Prizemlje" if floor == 0 else "I sprat",
        "outline": outline,
        "corridors": corridors,
        "paths": paths,
        "rooms": rooms,
        "stairs": stairs,
        "points": points,
        "voids": voids,
    }


PLANS = {f: floor_plan(f) for f in FLOORS}


def build():
    return build_graph(BUILDING, PLANS, stairs={key: FLOORS for key in STAIRS}, lifts={})


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.parse_args()
    g = build()
    write_all(g, PLANS, VIEWPORT, WALL, "Mašinski institut", "build_mi.py", "floor_plan_mi", "MI-0-ULAZ", CAMPUS_LINKS,
              source="fotografija evakuacionih planova (teren 03.10.2026)")


if __name__ == "__main__":
    main()
