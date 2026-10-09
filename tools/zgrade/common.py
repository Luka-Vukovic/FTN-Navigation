"""
Zajednički deo za planove zgrada iz FtnGO snimaka (build_nb.py, build_amf.py, build_kula.py).

Koordinate: jedan sistem za NB, Amfiteatre i Kulu - px plana NB, 1 px = M_PER_PX m u oba pravca, isti
smer (desno = sever, dole = istok). Spoljni zid svake zgrade (WALL) je njen OSM obris; u tom sistemu su
sva tri obrisa pravougaonici poravnati sa osama. Svaka zgrada ima svoj viewport (VIEWPORT), a smeštaj u
kampus je isti kao za NB (build_campus.py: rotacija i razmera NB, pomeraj iz viewport-a).

Opis sprata (dict):
  title       - naslov u XML komentaru
  outline     - spoljni obrisi (lista poligona); podrazumevano [WALL]
  roof        - krov nižih spratova (poligon), crta se ispod
  corridors   - hodnici (poligoni, samo crtež)
  paths       - srednje linije hodnika za graf (izlomljene linije); tačka jedne linije koja leži na drugoj
                je raskrsnica. Vrata, stepeništa, liftovi i posebne tačke se kače na najbližu tačku mreže
  rooms       - R(...): pravougaonik, naziv (None = bez čvora), vrata (podrazumevano sredina zida prema
                najbližoj liniji hodnika)
  stairs      - [(ključ, pravougaonik ili None, čvor)] - stepeništa; ključ povezuje spratove (vertical)
  flights     - {ključ stepeništa: Flights(...)} - stepenište sa dva kraka i međupodestom (teren 05.10.2026):
                krak naviše i krak naniže polaze sa podesta sprata, oba vode na međupodest. Čvor stepeništa je
                dno kraka naviše; na svakom spratu osim najnižeg ima i čvor <ključ>-D na dnu kraka naniže (tu se
                stiže odozdo), a ivica STEPENICE spaja <ključ> sprata sa <ključ>-D sprata iznad. Putanja (dno
                naviše, okret, okret, dno naniže) ide u JSON ("stairwells") - po njoj se tačka kreće na stepeništu.
                Može i lista varijanti (AMF S1: sa podesta levim ili desnim bočnim krakom); čvor -D je iz prve.
                Na najvišem spratu stepeništa nema čvora <ključ> (krak naviše ne postoji). Pravo stepenište
                (AMF S2: dva kraka u nizu) - sve četiri tačke putanje na jednoj liniji
  lifts       - [(ključ, pravougaonik, čvor)]
  points      - [(ključ, (x, y), tip)] - ulazi, prolazi (tip ULAZ/PROLAZ/HODNIK)
  columns, entrances, steps - samo crtež
"""

import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
M_PER_PX = 63.4 / 625  # NB: OSM obris 63,4 m = 625 px plana

# Oznaka HOD ivice sa stepenicima (krak do podesta/međunivoa, pasarela na podest) - ruta "bez stepenica" je ne koristi.
STEPS = "STEPENICI"


# Vrsta prostorije bez naziva koja ipak ide u graf (aplikacija: "najbliži toalet"). Čvor PROSTORIJA bez naziva.
TOALET = "TOALET"


def R(x0, x1, y0, y1, name=None, door=None, doors=None, amenity=None):
    """
    Soba; vrata: [door] (jedna tačka), [doors] (više ulaza) ili sredina zida prema hodniku. Soba bez naziva nema čvor,
    osim ako ima vrstu (amenity, npr. TOALET) - tada je čvor bez naziva sa vrstom.
    """
    return {"rect": (x0, y0, x1, y1), "name": name, "doors": doors or ([door] if door else None), "amenity": amenity}


def Flights(up, down, landing, path, draw_down=True, down_node=None, draw_up=True):
    """
    Krakovi stepeništa (vidi "flights" gore): up/down/landing - pravougaonici kraka naviše, kraka naniže i
    međupodesta (crtež); path - [dno kraka naviše, okret na međupodestu iznad njega, okret iznad kraka naniže,
    dno kraka naniže]. draw_down=False: krak naniže se ne crta (najniži sprat - ispod nema ničega). down_node: čvor
    <ključ>-D drugde od kraja putanje (AMF S1: gde se hodnik spaja sa oba bočna kraka). draw_up=False: krak naviše se
    ne crta (AMF S1 u prizemlju: srednji krak je ispod galerije, sa nje se vidi samo ograda nad praznim prostorom).
    """
    return {"up": up, "down": down, "landing": landing, "path": path, "draw_down": draw_down,
            "down_node": down_node or path[3], "draw_up": draw_up}


def variants(flights):
    """Varijante krakova stepeništa (Flights ili lista Flights)."""
    return flights if isinstance(flights, list) else [flights]


def row(y0, y1, spans):
    """Red soba: [(x0, x1, naziv ili None)] između y0 i y1."""
    return [R(a, b, y0, y1, name) for a, b, name in spans]


def col(x0, x1, spans):
    """Kolona soba: [(y0, y1, naziv ili None)] između x0 i x1."""
    return [R(x0, x1, a, b, name) for a, b, name in spans]


def rect_poly(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def project(p, a, b):
    """Najbliža tačka duži ab tački p i parametar t (0..1)."""
    dx, dy = b[0] - a[0], b[1] - a[1]
    t = 0.0 if dx == dy == 0 else max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)))
    return (a[0] + t * dx, a[1] + t * dy), t


class Graph:
    def __init__(self, building):
        self.building = building
        self.nodes = {}  # id -> (sprat, x, y, tip, naziv)
        self.edges = {}  # (a, b) -> tip
        self.steps = set()  # (a, b) HOD ivica sa stepenicima (krak do podesta/međunivoa) - "bez stepenica" ih ne koristi
        self.amenities = {}  # id -> vrsta prostorije bez naziva (TOALET)

    def nid(self, floor, key):
        return f"{self.building}-{'m' + str(-floor) if floor < 0 else floor}-{key}"

    def node(self, floor, key, x, y, kind="HODNIK", name=None):
        n = self.nid(floor, key)
        self.nodes.setdefault(n, (floor, round(x, 1), round(y, 1), kind, name))
        return n

    def edge(self, a, b, kind="HOD", steps=False):
        assert a in self.nodes and b in self.nodes, (a, b)
        if a != b and (b, a) not in self.edges:
            self.edges.setdefault((a, b), kind)
            if steps:
                self.steps.add((a, b))


class Network:
    """Mreža hodnika jednog sprata: izlomljene linije, na koje se kače vrata i ostale tačke."""

    def __init__(self, g, floor, paths):
        self.g, self.floor, self.paths = g, floor, [list(p) for p in paths]
        self.points = [{0.0: p[0]} for p in self.paths]  # po liniji: dužina od početka -> tačka
        for i, path in enumerate(self.paths):
            length = 0.0
            for a, b in zip(path, path[1:]):
                length += math.dist(a, b)
                self.points[i][length] = b

    def _key(self, p):
        return f"H{round(p[0])}_{round(p[1])}"

    def _nearest(self, p):
        best = None
        for i, path in enumerate(self.paths):
            length = 0.0
            for a, b in zip(path, path[1:]):
                q, t = project(p, a, b)
                d = math.dist(p, q)
                if best is None or d < best[0] - 1e-9:
                    best = (d, i, length + t * math.dist(a, b), q)
                length += math.dist(a, b)
        return best

    def attach(self, p):
        """Id čvora hodnika najbližeg tački p (dodaje ga na liniju)."""
        _, i, s, q = self._nearest(p)
        q = (round(q[0], 1), round(q[1], 1))
        self.points[i][round(s, 3)] = q
        return self.g.node(self.floor, self._key(q), *q)

    def finish(self):
        # Raskrsnice: teme jedne linije koje leži na drugoj.
        for j, path in enumerate(self.paths):
            for v in (path[0], path[-1]):
                d, i, s, q = self._nearest(v)
                if d < 0.6 and i != j:
                    self.points[i][round(s, 3)] = v
        for pts in self.points:
            ordered = [pts[s] for s in sorted(pts)]
            ids = [self.g.node(self.floor, self._key(p), *p) for p in ordered]
            for a, b in zip(ids, ids[1:]):
                self.g.edge(a, b)


def default_door(room, net):
    """Sredina zida sobe koji je najbliži mreži hodnika."""
    x0, y0, x1, y1 = room["rect"]
    sides = [((x0 + x1) / 2, y0), ((x0 + x1) / 2, y1), (x0, (y0 + y1) / 2), (x1, (y0 + y1) / 2)]
    return min(sides, key=lambda p: net._nearest(p)[0])


def build_floor(g, f, plan, down_keys=(), up_keys=None):
    """
    down_keys: stepeništa koja imaju sprat ispod ovog (čvor <ključ>-D na dnu kraka naniže, ako ima krakove);
    up_keys: stepeništa koja imaju sprat iznad (None = sva) - stepenište sa krakovima bez sprata iznad nema čvor <ključ>.
    """
    net = Network(g, f, plan["paths"])
    for room in plan["rooms"]:
        amenity = room.get("amenity")
        if not room["name"] and not amenity:
            continue
        x0, y0, x1, y1 = room["rect"]
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        key = room["name"] or f"{amenity}{round(cx)}_{round(cy)}"
        r = g.node(f, key, cx, cy, kind="PROSTORIJA", name=room["name"])
        if amenity:
            g.amenities[r] = amenity
        for door in room["doors"] or [default_door(room, net)]:
            d = g.node(f, f"V{round(door[0])}_{round(door[1])}", *door, kind="VRATA")
            g.edge(r, d)
            g.edge(d, net.attach(door))
    for key, _, at in plan.get("stairs", []):
        if key in plan.get("flights", {}) and up_keys is not None and key not in up_keys:
            continue
        g.edge(g.node(f, key, *at, kind="STEPENISTE"), net.attach(at))
    for key, flights in plan.get("flights", {}).items():
        if key in down_keys:
            foot = variants(flights)[0]["down_node"]
            g.edge(g.node(f, key + "-D", *foot, kind="STEPENISTE"), net.attach(foot))
    for key, _, at in plan.get("lifts", []):
        g.edge(g.node(f, key, *at, kind="LIFT"), net.attach(at))
    for key, at, kind, *via in plan.get("points", []):
        n = g.node(f, key, *at, kind=kind)
        if not via:
            g.edge(n, net.attach(at))
            continue
        # Tačka van mreže hodnika: (ključ postojećeg čvora, [(ključ, tačka), ...]) - npr. prolaz sa međupodesta
        # stepeništa (NB -> Amfiteatri): od čvora stepeništa preko podesta do prolaza. (ključ, tačka, STEPS): ivica do te
        # tačke ide krakom stepeništa (HOD, ali sa stepenicima - za rutu "bez stepenica").
        anchor, chain = via[0]
        prev = g.nid(f, anchor)
        for k, p, *mark in chain:
            cur = g.node(f, k, *p)
            g.edge(prev, cur, steps=STEPS in mark)
            prev = cur
        g.edge(prev, n)
    net.finish()


def build_graph(building, plans, stairs, lifts):
    """stairs: {ključ: [spratovi]} (susedni se povezuju), lifts: {ključ: [spratovi]} (svaka dva)."""
    g = Graph(building)
    for f, plan in plans.items():
        build_floor(g, f, plan, {key for key, floors in stairs.items() if f in floors and f != floors[0]},
                    {key for key, floors in stairs.items() if f in floors and f != floors[-1]})
    for key, floors in stairs.items():
        for a, b in zip(floors, floors[1:]):
            # Stepeništem sa krakovima se sa sprata ispod stiže na dno kraka naniže ("<ključ>-D").
            arrive = g.nid(b, key + "-D")
            g.edge(g.nid(a, key), arrive if arrive in g.nodes else g.nid(b, key), "STEPENICE")
    for key, floors in lifts.items():
        for i, a in enumerate(floors):
            for b in floors[i + 1:]:
                g.edge(g.nid(a, key), g.nid(b, key), "LIFT")
    return g


# --- Izlaz -------------------------------------------------------------------------------------

def stairwells_json(plans, rel):
    """Putanje stepeništa sa krakovima po spratu (relativno na viewport) - za kretanje tačke po stepeništu."""
    return [{"key": key, "floor": f, "path": [list(rel(*p)) for p in v["path"]]}
            for f, plan in plans.items() for key, fl in plan.get("flights", {}).items() for v in variants(fl)]


def write_json(g, path, viewport, floors, entrance, campus_links, indoor_links=(), stairwells=()):
    vx, vy, vw, vh = viewport

    def rel(x, y):
        return round((x - vx) / vw, 5), round((y - vy) / vh, 5)

    data = {
        "buildingId": g.building,
        "viewport": list(viewport),
        "floors": list(floors),
        "entranceId": entrance,
        "nodes": [
            {"id": n, "floor": f, "x": rel(x, y)[0], "y": rel(x, y)[1], "type": kind, **({"name": name} if name else {}),
             **({"amenity": g.amenities[n]} if n in g.amenities else {})}
            for n, (f, x, y, kind, name) in g.nodes.items()
        ],
        # [a, b, tip] ili [a, b, "HOD", STEPS] - hod krakom stepeništa (do podesta/međunivoa)
        "edges": [[a, b, kind] + ([STEPS] if (a, b) in g.steps else []) for (a, b), kind in g.edges.items()],
        # [čvor kampusa, čvor zgrade] ili [..., STEPS] - npr. pasarela F-bloka stiže na podest između spratova
        "campusLinks": [list(link) for link in campus_links],
        # [čvor ove zgrade, čvor druge zgrade sa planom] - prolaz mimo kampusa (npr. NB - Kula na I spratu)
        **({"indoorLinks": [list(link) for link in indoor_links]} if indoor_links else {}),
        **({"stairwells": stairwells_json(stairwells, rel)} if stairwells else {}),
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


def drawable(plan, viewport, wall, building_name, script, source="snimaka aplikacije FtnGO"):
    vx, vy, vw, vh = viewport
    outlines = plan.get("outline") or [rect_poly(*wall)]
    parts = []
    if "roof" in plan:
        parts.append(("Krov (niži spratovi)", path(fill=C_ROOF, stroke="#FFBDBDBD", width=1, data=poly(plan["roof"]))))
    parts.append(("Unutrašnjost", path(fill=C_BG, data=" ".join(poly(o) for o in outlines))))
    if plan.get("voids"):  # bez poda na ovom spratu (visoka hala ispod) - šrafirano kao na evakuacionom planu
        parts.append(("Praznine", path(fill=C_ROOF, data=" ".join(rect(*v) for v in plan["voids"]))
                      + path(stroke="#FFBDBDBD", width=0.8, data=" ".join(
                          f"M{x0},{y} H{x1}" for x0, y0, x1, y1 in plan["voids"] for y in range(int(y0) + 6, int(y1), 6)))))
    parts.append(("Hodnici", path(fill=C_CORRIDOR, data=" ".join(poly(p) for p in plan["corridors"]))))
    if plan.get("entrances"):
        parts.append(("Ulazi", path(fill=C_ENTRANCE, data=" ".join(rect(*e) for e in plan["entrances"]))))
    flights = plan.get("flights", {})
    stairs = [s[1] for s in plan.get("stairs", []) if s[1] and s[0] not in flights] + plan.get("steps", [])
    all_variants = [v for fl in flights.values() for v in variants(fl)]
    runs = list(dict.fromkeys(r for v in all_variants for r in ([v["up"]] if v["draw_up"] else []) + ([v["down"]] if v["draw_down"] else [])))
    if stairs or runs:
        parts.append(("Stepeništa", path(fill=C_STAIRS, data=" ".join(rect(*s) for s in stairs + runs))
                      + path(stroke=C_TREAD, width=0.8, data=" ".join(treads(*s) for s in stairs + runs))))
    if flights:
        landings = list(dict.fromkeys(v["landing"] for v in all_variants))
        parts.append(("Međupodesti", path(fill=C_STAIRS, stroke=C_TREAD, width=0.8,
                                          data=" ".join(rect(*l) for l in landings))))
    lifts = [l[1] for l in plan.get("lifts", []) if l[1]]
    if lifts:
        parts.append(("Liftovi", path(fill=C_LIFT, stroke=C_WALL, width=1.5, data=" ".join(rect(*l) for l in lifts))
                      + path(stroke=C_WALL, width=0.8, data=" ".join(
                          f"M{x0},{y0} L{x1},{y1} M{x1},{y0} L{x0},{y1}" for x0, y0, x1, y1 in lifts))))
    if plan.get("columns"):
        parts.append(("Stubovi", path(fill=C_WALL, data=" ".join(rect(x - 2, y - 2, x + 2, y + 2) for x, y in plan["columns"]))))
    # Soba sa "poly" (nije pravougaonik - ITC amfiteatar) crta se po njemu; "rect" ostaje za čvor i vrata.
    parts.append(("Prostorije", path(stroke=C_WALL, width=1.5, data=" ".join(
        poly(r["poly"]) if r.get("poly") else rect(*r["rect"]) for r in plan["rooms"]))))
    parts.append(("Spoljni zid", path(stroke=C_OUTER, width=3, data=" ".join(poly(o) for o in outlines))))
    body = "".join(f"\n        <!-- {name} -->\n{p}" for name, p in parts)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!--
    {plan["title"]} - {building_name}. GENERISANO skriptom tools/zgrade/{script} (ne menjati ručno).
    Šematski precrtano sa ispravljenih {source}; koordinate su px plana
    (zajednički sistem NB, AMF, Kule i F-bloka, 1 px = {M_PER_PX:.4f} m), grupa ih pomera u viewport.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{vw}dp"
    android:height="{vh}dp"
    android:viewportWidth="{vw}"
    android:viewportHeight="{vh}">

    <group
        android:translateX="{-vx}"
        android:translateY="{-vy}">
{body}
    </group>
</vector>
"""


def floor_res(prefix, f):
    return f"{prefix}_{'m' + str(-f) if f < 0 else f}"


def write_all(g, plans, viewport, wall, building_name, script, prefix, entrance, campus_links, indoor_links=(),
              source="snimaka aplikacije FtnGO"):
    res = ROOT / "app/src/main/res/drawable"
    for f, plan in plans.items():
        (res / f"{floor_res(prefix, f)}.xml").write_text(drawable(plan, viewport, wall, building_name, script, source), encoding="utf-8")
    asset = ROOT / f"app/src/main/assets/{g.building.lower()}.json"
    write_json(g, asset, viewport, list(plans), entrance, campus_links, indoor_links,
               plans if any(p.get("flights") for p in plans.values()) else ())
    rooms = sum(1 for n in g.nodes.values() if n[3] == "PROSTORIJA" and n[4])
    print(f"{g.building}: {len(g.nodes)} čvorova, {len(g.edges)} ivica, {rooms} sala -> {asset.name}")


def check_images(g, plans, viewport, wall, folder):
    """Crtež i graf preko ispravljenih snimaka (prepare_screens.py): <folder>/check<sprat>.png."""
    from PIL import Image, ImageDraw
    z = 3
    vx, vy = viewport[0], viewport[1]
    colors = {"HOD": (0, 90, 255), "STEPENICE": (255, 0, 0), "LIFT": (255, 0, 0)}
    P = lambda p: ((p[0] - vx) * z, (p[1] - vy) * z)
    for f, plan in plans.items():
        img = Image.open(folder / f"w{f}.png").convert("RGB")
        d = ImageDraw.Draw(img, "RGBA")
        for c in plan["corridors"]:
            d.polygon([P(p) for p in c], fill=(0, 120, 255, 40), outline=(0, 90, 255))
        for r in plan["rooms"]:
            x0, y0, x1, y1 = r["rect"]
            d.rectangle([P((x0, y0)), P((x1, y1))], outline=(160, 0, 200), width=2)
        for o in plan.get("outline") or [rect_poly(*wall)]:
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
