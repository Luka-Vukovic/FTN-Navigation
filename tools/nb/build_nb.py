"""
Nastavni blok (NB): šematski crteži svih 7 nivoa (-1 ... V sprat) i unutrašnji graf.

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/nb/build_nb.py [--check <folder sa w-1..w5.png>]

Pravi:
  - app/src/main/res/drawable/floor_plan_nb_{m1,0,1,2,3,4,5}.xml,
  - app/src/main/assets/nb.json (čvorovi, ivice, veze sa grafom kampusa; isti format kao ntp.json),
  - uz --check: <folder>/check{-1..5}.png - crtež i graf preko ispravljenih snimaka (prepare_screens.py).

Izvor: snimci ekrana veb aplikacije FtnGO (v0.8.4; images/nb-1.png ... nb5.png, van gita), ispravljeni
skriptom prepare_screens.py u koordinate ovog plana. Brojevi i nazivi sala su sa tih snimaka. Pre toga
je plan prizemlja bio precrtan sa evakuacionog plana (floor_plan_placeholder.xml) - raspored hodnika,
stepeništa, portirnice i ulaza se poklapa.

Koordinate: px plana, 1 px = M_PER_PX m u oba pravca; spoljni zid WALL je pravougaonik OSM obrisa
(63,4 x 21,1 m). Orijentacija kao ranije (tools/kampus/build_campus.py): desno = sever, dole = istok
(glavni ulaz), levo = jug (prolaz ka Kuli), gore = zapad (Amfiteatri).

Pojednostavljeno: vrata sale su na sredini zida prema hodniku (FtnGO vrata nisu precrtavana), čvor
hodnika je naspram vrata. V sprat je srednji pojas zgrade (potkrovlje); do njega vodi glavno stepenište
i spiralno stepenište iz hodnika IV sprata. Lift ide od -1 do IV sprata (na V spratu ga nema na snimku).
"""

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VX, VY, VW, VH = 305, 305, 750, 275  # viewport plana (px plana)
WALL = (413, 311, 1038, 519)  # spoljni zid: 625 x 208 px = OSM obris 63,4 x 21,1 m
M_PER_PX = 63.4 / 625
BUILDING = "NB"
FLOORS = [-1, 0, 1, 2, 3, 4, 5]
TOP_SLAB = (413, 368, 1038, 455)  # V sprat (prepare_screens.top_slab, zaokruženo)

STAIRS = (624, 318, 642, 377)  # krak glavnog stepeništa (levo od lifta)
LANDING = (642, 318, 691, 377)
LIFT = (643, 330, 664, 372)
STAIR_NODE, LIFT_NODE = (633, 382), (653, 382)
SPIRAL = (908, 424, 928, 440)  # spiralno stepenište IV -> V sprat
SPIRAL_NODE = (918, 432)


def fid(floor):
    return "m1" if floor < 0 else str(floor)


def nid(floor, key):
    return f"{BUILDING}-{fid(floor)}-{key}"


def R(x0, x1, y0, y1, name=None, door=None, doors=None):
    """Soba; vrata: [door] (jedna tačka), [doors] (više ulaza) ili sredina zida prema hodniku."""
    return {"rect": (x0, y0, x1, y1), "name": name, "doors": doors or ([door] if door else None)}


def row(y0, y1, spans):
    """Red soba: [(x0, x1, naziv ili None)] između y0 i y1."""
    return [R(a, b, y0, y1, name) for a, b, name in spans]


def rect_poly(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


# --- Spratovi ----------------------------------------------------------------------------------
# spine: (y, x0, x1) - srednja linija hodnika; vrata sala se kače na nju.

def floor_m1():
    return {
        "title": "Sprat -1 (suteren)",
        "spine": (431, 425, 955),
        "corridors": [[(416, 413), (624, 413), (624, 377), (691, 377), (691, 387), (760, 387), (760, 311),
                       (965, 311), (965, 519), (760, 519), (760, 450), (416, 450)]],
        "rooms": [
            *row(311, 413, [(416, 487, None), (487, 555, "P03")]),
            R(555, 625, 311, 369), R(555, 625, 369, 413, "P01"),
            *row(450, 519, [(416, 487, None), (487, 555, None), (555, 625, "P08"), (625, 665, None),
                            (665, 692, None), (692, 725, None), (725, 760, None)]),
            *row(311, 387, [(692, 727, "P21"), (727, 760, "P20")]),
            R(967, 1002, 311, 353, "P18", door=(967, 332)), R(967, 1002, 353, 412, "P17", door=(967, 382)),
            R(1002, 1038, 311, 412), R(965, 1038, 452, 519),
        ],
        "columns": [(830, 412), (898, 412), (830, 452), (898, 452)],
        "lift": True,
    }


def floor_0():
    return {
        "title": "Prizemlje",
        "spine": (420, 425, 1015),
        "corridors": [
            [(416, 383), (692, 383), (692, 378), (760, 378), (760, 405), (1038, 405), (1038, 456), (688, 456),
             (688, 519), (645, 519), (645, 456), (416, 456)],
            rect_poly(305, 403, 416, 442),  # spojni prolaz ka Kuli
            rect_poly(668, 305, 692, 311),  # prolaz ka Amfiteatrima (stepenicama naviše)
        ],
        "rooms": [
            *row(311, 383, [(416, 487, "018"), (487, 520, "019"), (520, 554, "021-3"), (554, 589, "021-2"),
                            (589, 624, "021-1")]),
            R(416, 452, 373, 407, "018A"),
            *row(311, 378, [(692, 727, None), (727, 760, "008")]),
            R(760, 1038, 311, 405, "Svečana sala"),
            *row(456, 519, [(416, 487, "015"), (487, 520, "013"), (520, 554, "014"), (554, 589, "016"),
                            (589, 618, "017"), (618, 645, "Portir"), (688, 760, None),
                            (760, 1003, "Studentska služba"), (1003, 1038, "002")]),
        ],
        "columns": [(452, 403), (487, 403), (554, 403), (622, 403), (487, 442), (554, 442), (622, 442)],
        "entrances": [(645, 519, 688, 527), (1034, 408, 1042, 452)],
        "steps": [(638, 527, 695, 545)],
        "lift": True,
    }


def floor_1():
    return {
        "title": "I sprat",
        "spine": (423, 425, 1025),
        "corridors": [rect_poly(416, 406, 1038, 441), rect_poly(624, 377, 691, 406)],
        "rooms": [
            *row(311, 406, [(416, 449, None), (449, 485, "102"), (485, 624, "101"), (691, 727, "113"),
                            (727, 760, "110"), (760, 932, "109A"), (932, 1038, "109")]),
            *row(441, 519, [(416, 520, "103"), (520, 588, "104"), (588, 691, "105"), (691, 759, "106"),
                            (759, 830, "107"), (830, 932, "108"), (932, 1038, "108A")]),
        ],
        "lift": True,
    }


def floor_2():
    return {
        "title": "II sprat",
        "spine": (415, 425, 1025),
        "corridors": [rect_poly(416, 395, 1038, 436), rect_poly(624, 377, 691, 395)],
        "rooms": [
            # 204/204A, 205/205A i 208/208A su po jedna velika učionica sa dva ulaza (korisnik; na FtnGO-u
            # su dve oznake, 204A čak sa zidom - verovatno ranije odvojene). "…A" su drugi ulazi (ROOM_ALIASES).
            *row(311, 395, [(416, 521, "202"), (521, 624, "201"), (691, 727, "212"), (727, 760, "209"),
                            (933, 1038, "207")]),
            R(760, 933, 311, 395, "208", doors=[(803, 395), (890, 395)]),
            *row(436, 519, [(416, 588, "203"), (934, 1038, "206")]),
            R(588, 760, 436, 519, "204", doors=[(640, 436), (726, 436)]),
            R(760, 934, 436, 519, "205", doors=[(803, 436), (890, 436)]),
        ],
        "lift": True,
    }


def floor_3():
    return {
        "title": "III sprat",
        "spine": (415, 425, 1025),
        "corridors": [rect_poly(416, 395, 1038, 436), rect_poly(624, 377, 691, 395)],
        "rooms": [
            # Računarski centar: FtnGO "L1 (301)", u rasporedu "L1 (RC)".
            *row(311, 395, [(416, 468, "L2 (RC)"), (468, 521, "L4 (RC)"), (521, 624, "L1 (RC)"),
                            (691, 727, "316"), (727, 760, "313"), (760, 830, "312"), (830, 933, "311"),
                            (933, 1038, "310")]),
            *row(436, 519, [(416, 484, "L3 (RC)"), (484, 521, "303A"), (521, 587, "L5 (RC)"),
                            (587, 639, "L6 (RC)"), (639, 691, "306A"), (691, 759, "306"), (759, 829, "307"),
                            (829, 932, "308"), (932, 1038, "309")]),
        ],
        "lift": True,
    }


def floor_4():
    return {
        "title": "IV sprat",
        "spine": (414, 405, 1025),
        "corridors": [[(416, 395), (1038, 395), (1038, 434), (416, 434), (416, 431), (400, 427), (396, 415),
                       (400, 403), (416, 399)],  # zaobljen južni kraj hodnika
                      rect_poly(624, 377, 691, 395)],
        "rooms": [
            *row(311, 395, [(416, 521, "AH1A"), (521, 554, None), (554, 624, "AH8"), (691, 728, "412"),
                            (728, 761, "410"), (761, 899, "AH7"), (899, 1038, "AH6")]),
            *row(434, 519, [(416, 554, "AH1B"), (554, 659, "AH2"), (659, 762, "AH3"), (762, 831, "AH4"),
                            (831, 900, "AH4A"), (900, 934, "406"), (934, 1038, "AH5")]),
        ],
        "spiral": True,
        "lift": True,
    }


def floor_5():
    x0, y0, x1, y1 = TOP_SLAB
    return {
        "title": "V sprat (potkrovlje)",
        "roof": rect_poly(*WALL),
        "outline": rect_poly(*TOP_SLAB),
        "spine": (402, 528, 961),
        "corridors": [rect_poly(520, 394, 969, 411), rect_poly(624, y0, 691, 394)],
        "rooms": [
            R(x0, 520, y0, y1, "AH-CRT", door=(520, 402)),  # FtnGO "ARH CRT (502)"
            R(969, x1, y0, y1, "AH9", door=(969, 402)),  # FtnGO "AH9 (517)"
            *row(y0, 394, [(520, 554, "501A"), (554, 624, "501"), (691, 726, "500"), (726, 763, "509A"),
                           (763, 797, None), (797, 830, "512")]),
            *row(y0, 400, [(830, 900, "520"), (900, 934, "519"), (934, 969, "518")]),
            *row(411, y1, [(520, 554, "503"), (554, 589, "504"), (589, 623, None), (623, 658, "505"),
                           (658, 691, "506"), (691, 762, "509"), (762, 797, "510"), (797, 831, "511")]),
            R(831, 901, 416, y1, "521"),
            R(934, 951, 415, 435, "515"), R(951, 969, 415, 435, "516"),
        ],
        "stairs_rect": (624, y0, 645, 394),
        "stair_node": (635, 398),
        "spiral": True,
        "lift": False,
    }


PLANS = {-1: floor_m1(), 0: floor_0(), 1: floor_1(), 2: floor_2(), 3: floor_3(), 4: floor_4(), 5: floor_5()}

# Posebni čvorovi prizemlja: ulaz, prolazi, predvorje. Veze sa kampusom (build_campus.py pravi te čvorove).
ENTRANCE = (666, 522)
VESTIBULE = (666, 480)
PASSAGE_KULA = (322, 422)
PASSAGE_AMF = (680, 313)
CAMPUS_LINKS = [("K-U-NB-1", "ULAZ"), ("K-P-NB-KULA", "PROLAZ"), ("K-P-AMF-NB", "PROLAZ-AMF")]


# --- Graf --------------------------------------------------------------------------------------

class Graph:
    def __init__(self):
        self.nodes = {}  # id -> (sprat, x, y, tip, naziv)
        self.edges = {}  # (a, b) -> tip

    def node(self, floor, key, x, y, kind="HODNIK", name=None):
        n = nid(floor, key)
        self.nodes.setdefault(n, (floor, round(x, 1), round(y, 1), kind, name))
        return n

    def edge(self, a, b, kind="HOD"):
        assert a in self.nodes and b in self.nodes, (a, b)
        if a != b and (b, a) not in self.edges:
            self.edges.setdefault((a, b), kind)


def build_floor(g, f, plan):
    sy, sx0, sx1 = plan["spine"]
    spine = {}  # x -> id čvora hodnika

    def attach(x):
        x = round(min(max(x, sx0), sx1))
        if x not in spine:
            spine[x] = g.node(f, f"H{x}", x, sy)
        return spine[x]

    attach(sx0)
    attach(sx1)
    for room in plan["rooms"]:
        if not room["name"]:
            continue
        x0, y0, x1, y1 = room["rect"]
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        r = g.node(f, room["name"], cx, cy, kind="PROSTORIJA", name=room["name"])
        for door in room["doors"] or [(cx, y1) if y1 <= sy else (cx, y0)]:
            d = g.node(f, f"V{round(door[0])}_{round(door[1])}", *door, kind="VRATA")
            g.edge(r, d)
            g.edge(d, attach(door[0]))

    stair = plan.get("stair_node", STAIR_NODE)
    g.edge(g.node(f, "S", *stair, kind="STEPENISTE"), attach(stair[0]))
    if plan["lift"]:
        g.edge(g.node(f, "L", *LIFT_NODE, kind="LIFT"), attach(LIFT_NODE[0]))
    if plan.get("spiral"):
        g.edge(g.node(f, "S2", *SPIRAL_NODE, kind="STEPENISTE"), attach(SPIRAL_NODE[0]))
    if f == 0:
        v = g.node(f, "PREDVORJE", *VESTIBULE)
        g.edge(v, attach(VESTIBULE[0]))
        g.edge(g.node(f, "ULAZ", *ENTRANCE, kind="ULAZ"), v)
        g.edge(g.node(f, "PROLAZ", *PASSAGE_KULA, kind="PROLAZ"), attach(sx0))
        g.edge(g.node(f, "PROLAZ-AMF", *PASSAGE_AMF, kind="PROLAZ"), attach(PASSAGE_AMF[0]))
    xs = sorted(spine)
    for a, b in zip(xs, xs[1:]):
        g.edge(spine[a], spine[b])


def build():
    g = Graph()
    for f, plan in PLANS.items():
        build_floor(g, f, plan)
    for a, b in zip(FLOORS, FLOORS[1:]):
        g.edge(nid(a, "S"), nid(b, "S"), "STEPENICE")
    g.edge(nid(4, "S2"), nid(5, "S2"), "STEPENICE")
    lift_floors = [f for f in FLOORS if PLANS[f]["lift"]]
    for i, a in enumerate(lift_floors):
        for b in lift_floors[i + 1:]:
            g.edge(nid(a, "L"), nid(b, "L"), "LIFT")
    return g


# --- Izlaz -------------------------------------------------------------------------------------

def rel(x, y):
    return round((x - VX) / VW, 5), round((y - VY) / VH, 5)


def write_json(g, path):
    data = {
        "buildingId": BUILDING,
        "viewport": [VX, VY, VW, VH],
        "floors": FLOORS,
        "entranceId": nid(0, "ULAZ"),
        "nodes": [
            {"id": n, "floor": f, "x": rel(x, y)[0], "y": rel(x, y)[1], "type": kind, **({"name": name} if name else {})}
            for n, (f, x, y, kind, name) in g.nodes.items()
        ],
        "edges": [[a, b, kind] for (a, b), kind in g.edges.items()],
        "campusLinks": [[campus, nid(0, key)] for campus, key in CAMPUS_LINKS],
    }
    path.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


C_BG, C_ROOF, C_CORRIDOR, C_STAIRS, C_LIFT = "#FFFFFFFF", "#FFF0F0F0", "#FFE8EEF4", "#FFFFE0B2", "#FFFFCC80"
C_ENTRANCE, C_WALL, C_OUTER, C_TREAD = "#FFC8E6C9", "#FF455A64", "#FF263238", "#FF8D6E63"


def poly(pts):
    return "M" + " L".join(f"{x:g},{y:g}" for x, y in pts) + " Z"


def rect(x0, y0, x1, y1):
    return poly(rect_poly(x0, y0, x1, y1))


def treads(x0, y0, x1, y1, step=6):
    """Gazišta: linije popreko duže strane stepeništa."""
    if x1 - x0 >= y1 - y0:
        return " ".join(f"M{x},{y0} V{y1}" for x in range(int(x0) + step, int(x1), step))
    return " ".join(f"M{x0},{y} H{x1}" for y in range(int(y0) + step, int(y1), step))


def path(fill=None, stroke=None, width=None, data=""):
    attrs = [f'android:pathData="{data}"']
    if fill:
        attrs.insert(0, f'android:fillColor="{fill}"')
    if stroke:
        attrs.insert(0, f'android:strokeColor="{stroke}"')
        attrs.insert(1, f'android:strokeWidth="{width}"')
    return "        <path\n            " + "\n            ".join(attrs) + " />\n"


def drawable(f, plan):
    outline = plan.get("outline", rect_poly(*WALL))
    parts = []
    if "roof" in plan:
        parts.append(("Krov (niži spratovi)", path(fill=C_ROOF, stroke="#FFBDBDBD", width=1, data=poly(plan["roof"]))))
    parts.append(("Unutrašnjost", path(fill=C_BG, data=poly(outline))))
    parts.append(("Hodnici", path(fill=C_CORRIDOR, data=" ".join(poly(p) for p in plan["corridors"]))))
    if plan.get("entrances"):
        parts.append(("Ulazi", path(fill=C_ENTRANCE, data=" ".join(rect(*e) for e in plan["entrances"]))))
    stairs = [plan.get("stairs_rect", STAIRS)] + plan.get("steps", []) + ([SPIRAL] if plan.get("spiral") else [])
    parts.append(("Stepeništa", path(fill=C_STAIRS, data=" ".join(rect(*s) for s in stairs))
                  + path(stroke=C_TREAD, width=0.8, data=" ".join(treads(*s) for s in stairs))))
    if plan["lift"]:
        x0, y0, x1, y1 = LIFT
        parts.append(("Lift", path(fill=C_LIFT, stroke=C_WALL, width=1.5, data=rect(*LIFT))
                      + path(stroke=C_WALL, width=0.8, data=f"M{x0},{y0} L{x1},{y1} M{x1},{y0} L{x0},{y1}")))
    if plan.get("columns"):
        parts.append(("Stubovi", path(fill=C_WALL, data=" ".join(rect(x - 2, y - 2, x + 2, y + 2) for x, y in plan["columns"]))))
    parts.append(("Prostorije", path(stroke=C_WALL, width=1.5, data=" ".join(rect(*r["rect"]) for r in plan["rooms"]))))
    parts.append(("Spoljni zid", path(stroke=C_OUTER, width=3, data=poly(outline))))
    body = "".join(f"\n        <!-- {name} -->\n{p}" for name, p in parts)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!--
    {plan["title"]} - Nastavni blok. GENERISANO skriptom tools/nb/build_nb.py (ne menjati ručno).
    Šematski precrtano sa ispravljenih snimaka aplikacije FtnGO; koordinate su px plana
    (1 px = {M_PER_PX:.4f} m), grupa ih pomera u viewport.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{VW}dp"
    android:height="{VH}dp"
    android:viewportWidth="{VW}"
    android:viewportHeight="{VH}">

    <group
        android:translateX="{-VX}"
        android:translateY="{-VY}">
{body}
    </group>
</vector>
"""


def check_images(g, folder):
    from PIL import Image, ImageDraw
    z = 3
    colors = {"HOD": (0, 90, 255), "STEPENICE": (255, 0, 0), "LIFT": (255, 0, 0)}
    P = lambda p: ((p[0] - VX) * z, (p[1] - VY) * z)
    for f, plan in PLANS.items():
        img = Image.open(folder / f"w{f}.png").convert("RGB")
        d = ImageDraw.Draw(img, "RGBA")
        for c in plan["corridors"]:
            d.polygon([P(p) for p in c], fill=(0, 120, 255, 40), outline=(0, 90, 255))
        for r in plan["rooms"]:
            x0, y0, x1, y1 = r["rect"]
            d.rectangle([P((x0, y0)), P((x1, y1))], outline=(160, 0, 200), width=2)
        o = plan.get("outline", rect_poly(*WALL))
        d.line([P(p) for p in o + [o[0]]], fill=(0, 0, 0), width=3)
        for (a, b), kind in g.edges.items():
            na, nb = g.nodes[a], g.nodes[b]
            if na[0] == nb[0] == f:
                d.line([P(na[1:3]), P(nb[1:3])], fill=colors[kind], width=3)
        for n, (fl, x, y, kind, name) in g.nodes.items():
            if fl != f:
                continue
            x, y = P((x, y))
            r = 7 if kind in ("PROSTORIJA", "STEPENISTE", "LIFT", "ULAZ", "PROLAZ") else 4
            d.ellipse((x - r, y - r, x + r, y + r), fill=(255, 0, 0) if kind != "PROSTORIJA" else (0, 160, 0))
            if name:
                d.text((x + 9, y - 5), name, fill=(0, 100, 0))
        img.save(folder / f"check{f}.png")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", type=Path, help="folder sa w-1..w5.png (prepare_screens.py)")
    args = parser.parse_args()
    g = build()
    res = ROOT / "app/src/main/res/drawable"
    for f, plan in PLANS.items():
        (res / f"floor_plan_nb_{fid(f)}.xml").write_text(drawable(f, plan), encoding="utf-8")
    write_json(g, ROOT / "app/src/main/assets/nb.json")
    rooms = sum(1 for n in g.nodes.values() if n[3] == "PROSTORIJA")
    print(f"NB: {len(g.nodes)} čvorova, {len(g.edges)} ivica, {rooms} sala")
    if args.check:
        check_images(g, args.check)


if __name__ == "__main__":
    main()
