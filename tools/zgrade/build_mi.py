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
import re

from common import R, build_graph, rect_poly, write_all

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
    "B": {0: [-1121, -1023, -991, -937, -926, -893, -778, -742], 1: [-1121, -1087, -1056, -957, -923, -860, -828, -795, -763, -742]},
    "V": {0: [-1121, -957, -937, -860, -796, -742], 1: [-957, -925, -860, -800, -756, -742]},
    "G": {1: [-1121, -1053, -1022, -956, -925, -891, -829]},
    "D": {0: [-1121, -1085, -1054, -924, -907, -826, -794, -742], 1: [-1121, -1056, -924, -891, -827, -793, -765, -742]},
    "Đ": {0: [-1121, -1054, -989, -942, -924, -891, -859, -794, -762, -742], 1: [-1121, -1055, -990, -924, -858, -793, -742]},
}
# Pregrade popreko krila (x) u sobi između dva zida iz WING_WALLS: (krilo, sprat, y0) -> [x...]. Teren 10.10.2026
# (korisnik: plan MI nije dovoljno tačan u odnosu na slike) - pročitano sa uvećanih isečaka ispravljenih fotografija.
WING_SPLITS = {
    ("A", 0, -1088): [-82], ("A", 0, -795): [-101], ("V", 0, -937): [374],
    ("D", 0, -1121): [825], ("D", 0, -1085): [817], ("Đ", 0, -762): [960], ("D", 1, -1121): [852],
}
# Krilo G u prizemlju (nepravilno): hala sa sobom u uglu, prolaz, sobe oko stepeništa SG, hala ka dugačkom delu.
G0_HALL = [(455, -1121), (552, -1121), (552, -1022), (492, -1022), (492, -958), (455, -958)]
G0_ROOMS = [(492, 552, -1022, -958), (505, 552, -945, -925), (502, 552, -925, -860), (455, 502, -905, -860),
            (455, 552, -860, -742)]
# Uz krilo B spolja (istočno): magacin i kompresorska stanica - nema ih u OSM obrisu.
B_ANNEX = [(97, 139, -926, -894), (97, 121, -892, -859)]
B0_PASSAGE = [(-2, -937), (97, -937), (97, -926), (-2, -926)]  # prizemlje: prolaz od hodnika A|B do istočnog izlaza
# Krilo B, I sprat (korisnik 10.10.2026: "pogledaj B krilo bolje, na prvom spratu"): soba ispod gornje je uža - levo
# od nje je predvorje uz kraj hodnika A|B; između -957 i -923 nisu jedna soba nego mala soba, prolaz od hodnika i
# predsoblje sa dvokrilnim vratima ka sobama iznad i ispod, pa soba desno.
WING_OVERRIDE = {("B", 1, -1087): [(17, 97, -1087, -1056)], ("B", 1, -957): [(1, 35, -945, -923), (56, 97, -957, -923)]}
B1_NOOK = [(-2, -1087), (15, -1087), (15, -1056), (-2, -1056)]
B1_PASSAGE = [(-2, -957), (56, -957), (56, -923), (35, -923), (35, -945), (-2, -945)]
AB1_TOP = -1076  # hodnik A|B na spratu počinje ispod sobe na kraju kolone hodnika
AB1_STEPS = (-31, -828, -17, -764)  # stepenice u hodniku A|B na spratu (samo crtež)
# Kolona hodnika između krila u prizemlju: hodnik V|G počinje tek kod -994 (iznad su dve sobe), D|Đ kod -1086.
CORRIDOR_START = {"VG": -994, "DĐ": -1086}
CORRIDOR_ROOMS = {"VG": [(-1121, -1047), (-1047, -994)], "DĐ": [(-1121, -1086)]}
ANNEX_WALLS = [-955, -792, -742]
ANNEX_STORE = (735, 780, -792, -764)  # magacin uz učionicu u dogradnji; ostalo je prolaz ka evakuacionom izlazu
# I sprat: šrafirano na planu - visoke hale bez poda na spratu (i spoljne stepenice krila A).
VOIDS = {
    "V": [(-1121, -957), (-860, -800)], "G": [(-829, -742)], "D": [(-1056, -924), (-765, -742)], "Đ": [(-858, -793)],
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
    if wing == "G" and floor == 0:
        return sorted(G0_ROOMS, key=lambda r: (-r[3], r[0]))
    walls = WING_WALLS[wing].get(floor, [])
    x0, x1 = WINGS[wing]
    rects = []
    for a, b in zip(walls, walls[1:]):
        if b - a < 15 or in_voids(wing, a, b, floor):
            continue
        if (wing, floor, a) in WING_OVERRIDE:
            rects += WING_OVERRIDE[(wing, floor, a)]
            continue
        xs = [x0] + WING_SPLITS.get((wing, floor, a), []) + [x1]
        rects += [(p, q, a, b) for p, q in zip(xs, xs[1:])]
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
        if wing == "Đ":  # levo od hodnika u Đ, ispod stepeništa SĐ: dve sobe (pregrada na -958)
            rects = [q for r in rects for q in ([(r[0], r[1], r[2], -958), (r[0], r[1], -958, r[3])]
                                                if r[1] <= cx0 and r[2] < -958 < r[3] else [r])]
    return sorted(rects, key=lambda r: (-r[3], r[0]))


def block(name):
    """Soba oznake bez slova na kraju ("B4-0A" -> "B4-0", "B4-1A" -> "B4-1", "B1-A" -> "B1", "G3-1C" -> "G3-1") -
    oznake koje se razlikuju samo slovom su delovi iste sobe (PRETPOSTAVKA)."""
    return re.sub(r"(?<=\d)[A-D]$|-[A-D]$", "", name)


ALIASES = {}  # druga oznaka -> oznaka sobe (popunjava wing_rooms; ide u ROOM_ALIASES u Destinations.kt)


def wing_rooms(wing, floor):
    """Sobe krila tačno kao na evakuacionom planu; sale (oznake bez slova na kraju, block) redom od dugačkog dela po
    sobama - PRETPOSTAVKA. Kad je oznaka više nego soba, više njih deli sobu: prva je soba, ostale su druge oznake
    (ALIASES).
    Do 10.10.2026 se soba delila nacrtano na onoliko delova koliko ima oznaka - tih pregrada na planu nema."""
    names = WING_ROOMS.get(wing, {}).get(floor, [])
    rects = wing_room_rects(wing, floor)
    blocks = list(dict.fromkeys(block(n) for n in names))
    groups = [[] for _ in rects]
    for i, b in enumerate(blocks):
        groups[min(len(rects) - 1, i * len(rects) // len(blocks))] += [n for n in names if block(n) == b]
    out = []
    for (x0, x1, y0, y1), group in zip(rects, groups):
        out.append(R(x0, x1, y0, y1, f"MI {group[0]}" if group else None))
        for other in group[1:]:
            ALIASES[f"MI {other}"] = f"MI {group[0]}"
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
        outline = [OUTLINE, ANNEX] + [rect_poly(x0, y0, x1, y1) for x0, x1, y0, y1 in B_ANNEX]
        rooms += [R(712, 780, a, b) for a, b in zip(ANNEX_WALLS[:-2], ANNEX_WALLS[1:-1])] + [R(*ANNEX_STORE)]
        rooms += [R(*r) for r in B_ANNEX]
        hall = R(455, 552, -1121, -958)
        hall["poly"] = G0_HALL
        rooms.append(hall)
        for key, (x0, x1) in WING_CORRIDORS.items():
            top = CORRIDOR_START.get(key, WING_END)
            corridors.append([(x0, top), (x1, top), (x1, BAR_Y[0]), (x0, BAR_Y[0])])
            paths.append([((x0 + x1) / 2, y_bar), ((x0 + x1) / 2, max(top + 10, -1110))])
            rooms += [R(x0, x1, a, b) for a, b in CORRIDOR_ROOMS.get(key, [])]
        corridors.append(B0_PASSAGE)
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
            top = AB1_TOP if key == "AB" else -1110
            corridors.append([(x0, top), (x1, top), (x1, BAR_Y[0]), (x0, BAR_Y[0])])
            paths.append([((x0 + x1) / 2, y_bar), ((x0 + x1) / 2, top + 5)])
        x0, x1 = WING_CORRIDORS["AB"]
        rooms.append(R(x0, x1, -1121, AB1_TOP))  # soba na kraju kolone hodnika A|B
        corridors += [B1_NOOK, B1_PASSAGE]
        x_ab = (x0 + x1) / 2
        # Krak ka predvorju B počinje TAČNO na kraju hodnika A|B (top + 5) - na -1072 (1 px pored) nije bio spojen, pa su
        # A3Gal./2 i B4-5 bile nedostižne (10.10.2026).
        paths += [[(x_ab, top_ab := AB1_TOP + 5), (6, top_ab)], [(x_ab, -951), (45, -951)]]
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
        "steps": [AB1_STEPS] if floor == 1 else [],
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
    print("Druge oznake (ROOM_ALIASES u Destinations.kt):")
    print(", ".join(f'"{a}" to "{b}"' for a, b in ALIASES.items()))


if __name__ == "__main__":
    main()
