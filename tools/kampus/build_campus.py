"""
Pravi spoljnu mapu kampusa FTN-a iz OpenStreetMap-a: obrise zgrada, pešačke staze i graf
za rutiranje između zgrada. Rezultat je JSON koji aplikacija učitava iz assets-a.

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/kampus/build_campus.py \
        -o app/src/main/assets/campus.json [--osm osm_snimak.json]

--osm: ako fajl postoji, podaci se čitaju iz njega (bez mreže), inače se preuzimaju sa
Overpass API-ja i snimaju tu. Bez opcije se uvek preuzima sveže.

Podaci su © OpenStreetMap contributors (ODbL) - aplikacija to mora da navede.

Koordinate: lokalna projekcija (ekvidistantna) oko REF_LAT/REF_LON, u metrima; x raste ka
istoku, y ka jugu (kao na ekranu). U JSON-u su pomerene tako da gornji levi ugao oblasti
bude (0, 0).

Šta je ručno zadato (terenski podaci, vidi images/ van gita):
  - koje OSM zgrade su FTN zgrade i kako se zovu,
  - studentske službe van FTN-a (menza, zdravstvena zaštita...): zgrada ili tačka službe u
    zgradi (images/službe.png),
  - spojni prolazi između zgrada (u OSM-u su zasebni delovi zgrada),
  - ulazi koji se koriste: OSM čvor entrance=* ili tačka koja se "lepi" na zid zgrade,
  - smeštaj planova Nastavnog bloka, Amfiteatara, Kule i F-bloka (tools/zgrade, zajednički sistem) u obrise,
  - smeštaj plana NTP-a (tools/ntp/build_ntp.py) i njegovi ulazi.
Zgrade bez unutrašnjeg plana u grafu su jedan čvor (ZGRADA) povezan sa ulazima i prolazima.
"""

import argparse
import datetime as dt
import json
import math
import sys
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ntp"))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "zgrade"))
import build_amf  # noqa: E402 - planovi NB, Amfiteatara, Kule i F-bloka: zajednički sistem, viewport-i, ulazi
import build_f  # noqa: E402
import build_mi  # noqa: E402
import build_kula  # noqa: E402
import build_nb  # noqa: E402
import build_ntp  # noqa: E402 - plan NTP-a: viewport i ulazi

# Projekcija (REF_*, M_PER_DEG_*, X_MIN, Y_MIN) je prepisana u aplikaciju za GPS: campus/CampusGeo.kt - menjati oba.
REF_LAT, REF_LON = 45.2455, 19.8500
M_PER_DEG_LAT = 110540.0
M_PER_DEG_LON = 111320.0 * math.cos(math.radians(REF_LAT))

# Oblast mape u metrima od referentne tačke: sve FTN zgrade + okolina.
X_MIN, X_MAX, Y_MIN, Y_MAX = -235.0, 215.0, -195.0, 150.0

# id u aplikaciji, naziv, kratak natpis na mapi, OSM element obrisa
BUILDINGS = [
    ("NB", "Nastavni blok", "Nastavni blok", "way", 250277315),
    ("AMF", "Amfiteatri", "Amfiteatri", "way", 250277314),
    ("F", "F-blok", "F-blok", "way", 250277316),
    ("KULA", "Kula", "Kula", "way", 222832765),
    ("ITC", "Istraživačko-tehnološki centar", "ITC", "way", 250277317),
    ("MI", "Mašinski institut", "Mašinski institut", "way", 222832768),
    ("NTP", "Naučno-tehnološki park", "NTP", "relation", 17305144),
    ("DGG", "Departman za građevinarstvo i geodeziju", "Građevinarstvo", "way", 148672026),
]

# Studentske službe (nisu FTN, na mapi su drugačije obeležene): id, naziv, natpis, OSM
# obris zgrade i tačka službe (lat, lon) ako je služba samo deo zgrade - tu stoji natpis i
# čvor grafa. Više službi u istoj zgradi dele obris (crta se jednom).
SERVICES = [
    ("MENZA", "Menza", "Menza", "way", 222835154, None),  # Studentski restoran 2 (Velika menza)
    ("ZZZS", "Zavod za zdravstvenu zaštitu studenata", "Zdravstvena zaštita", "way", 222835153, None),
    # U studentskom domu "Slobodan Bajić"; tačke su OSM čvorovi 6432341246 i 6432341247.
    ("SMESTAJ", "Služba smeštaja", "Služba smeštaja", "relation", 2955597, (45.2455021, 19.8492921)),
    ("ISHRANA", "Blagajna ishrane", "Blagajna ishrane", "relation", 2955597, (45.2454119, 19.8489912)),
    # Pošta Srbije 21125, OSM čvor 9945489639 (amenity=post_office) u zgradi Amfiteatara, ulaz spolja
    # (korisnik, 30.09.2026). Nije u snimku OSM-a (snimak ima samo zgrade, staze i ulaze) - tačka je upisana.
    ("POSTA", "Pošta", "Pošta", "way", 250277314, (45.2460477, 19.8513347)),
]

# Natpis koji ne sme da bude centriran na tački (preklapao bi se sa susednim na početnom
# zumu - natpisi ostaju iste veličine na ekranu): EAST = počinje od tačke ka istoku,
# WEST = završava se na tački.
LABEL_SIDE = {"SMESTAJ": "EAST", "ISHRANA": "WEST", "ZZZS": "WEST"}

# Zgrade čiji OSM unutrašnji prstenovi nisu dvorišta (provereno na terenu) - crtaju se pune.
NO_HOLES = {"NTP"}

# Zgrade čiji unutrašnji graf postoji u aplikaciji (assets/nb.json, amf.json, kula.json, f.json, ntp.json, mi.json):
# za njih se ne pravi čvor ZGRADA - ulazi i prolazi se u aplikaciji vezuju za čvorove unutrašnjeg grafa.
WITH_INTERIOR = {"NB", "NTP", "AMF", "KULA", "F", "MI"}

# Spojni prolazi (unutrašnje veze), OSM way zasebnog dela zgrade između njih.
PASSAGES = [
    ("ITC", "AMF", 250277324),
    ("AMF", "NB", 250277326),
    ("NB", "KULA", 250277327),
    ("AMF", "KULA", 250277322),
    ("AMF", "F", 250277323),
]

# Ulazi koji se koriste. int = OSM čvor entrance=*; (lat, lon) = tačka sa terenske mape,
# lepi se na najbliži zid zgrade (OSM tu nema ulaz).
ENTRANCES = {
    "ITC": [2317759575],
    "NB": [11691291600],  # glavni ulaz, prema Trgu Dositeja Obradovića
    "KULA": [2317759457],
    # 2: ulaz GRID-a (Grafičko inženjerstvo i dizajn) sa zapada; označio korisnik na images/entrances 1.png
    # (29.09.2026), preračunat preko poznatih ulaza i prolaza (greška 1-4 m). Od 30.09.2026 GRID je deo plana
    # Amfiteatara (GRID-1, GRID-2 na -1), pa je ovo običan drugi ulaz (ranije poseban deo zgrade K-Z-GRID).
    "AMF": [11691291594, (45.246285, 19.851143)],
    "MI": [2317759407],
    # NTP: ulazi FTN dela sa plana prizemlja (build_ntp.ENTRANCES), preko smeštaja plana - bez lepljenja
    # na zid. OSM ulazi 13123222553 (istok) i 13123222559 (Fruškogorska) se ne koriste (sa razmerom 0,042
    # su bili 9-20 m od krajeva PASAŽA; proveriti ponovo).
    "NTP": [("plan", x, y) for (x, y), _ in build_ntp.ENTRANCES.values()],
    # F: bez spoljnog ulaza - samo pasarela iz Amfiteatara (korisnik, 02.10.2026: "ne znam da li se koristi stvarno
    # neki spoljni ulaz"). Ranije (29.09.) ulaz sa juga (45.245662, 19.851880) - evakuacioni plan tu nema vrata.
    "DGG": [(45.244697, 19.850246)],  # istočna strana, gornja trećina
    "MENZA": [13123222548],  # istočni ugao, kod Restorana 10
    "ZZZS": [2317805668],  # istočna strana, prema Dr Sime Miloševića
    # Službe u domu: ulaz sa severne strane, naspram tačke službe.
    "SMESTAJ": [(45.2455021, 19.8492921)],
    "ISHRANA": [(45.2454119, 19.8489912)],
    "POSTA": [(45.2460477, 19.8513347)],  # zalepljeno za najbliži (zapadni) zid Amfiteatara
}


# Plan Nastavnog bloka (tools/nb/build_nb.py) je u px plana; spoljni zid je pravougaonik OSM obrisa
# (od 01.10.2026; ranije px fotografije evakuacionog plana, zid y 311..504 - 7 % preširoko poprečno).
# Orijentacija (provereno glavnim ulazom, prolazom ka Kuli i zbornim mestom "kod fontane"): desno na
# planu = severni kraj zgrade, dole = istočna strana (glavni ulaz), levo = južni kraj (prolaz ka Kuli).
NB_PLAN_WALL = tuple(float(v) for v in build_nb.WALL)
NB_PLAN_VIEWPORT = (float(build_nb.VX), float(build_nb.VY), float(build_nb.VW), float(build_nb.VH))

# Plan NTP-a (FTN deo): smeštaj (razmera, vrh, transformacija) je u tools/ntp/build_ntp.py (M_PER_PX,
# plan_transform) - tamo su i provere sa terena (01.10.2026: 0,029 m/px; ranije pretpostavljenih 0,042 je
# bilo ~1,45x previše). Zgrada NTP-a u OSM-u je ceo NTP: FTN deo je severni kraj, južno je poslovni deo.

WALKABLE = {
    "footway", "path", "pedestrian", "steps", "service", "living_street", "residential",
    "unclassified", "tertiary", "track", "corridor",
}
ROADS = {"residential", "service", "living_street", "unclassified", "tertiary", "secondary", "secondary_link", "primary"}
FOOTPATHS = {"footway", "path", "pedestrian", "steps", "cycleway", "track"}

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
USER_AGENT = "FTNNavigation-diplomski/0.1 (tools/kampus/build_campus.py)"


def to_xy(lat, lon):
    return ((lon - REF_LON) * M_PER_DEG_LON - X_MIN, (REF_LAT - lat) * M_PER_DEG_LAT - Y_MIN)


def to_latlon(x, y):
    return (REF_LAT - (y + Y_MIN) / M_PER_DEG_LAT, REF_LON + (x + X_MIN) / M_PER_DEG_LON)


def fetch_osm():
    s, w = to_latlon(0, Y_MAX - Y_MIN)
    n, e = to_latlon(X_MAX - X_MIN, 0)
    bbox = f"{s:.6f},{w:.6f},{n:.6f},{e:.6f}"
    query = f"""[out:json][timeout:90];
(
  way["building"]({bbox});
  relation["building"]({bbox});
  way["highway"]({bbox});
  node["entrance"]({bbox});
);
out body geom;"""
    request = urllib.request.Request(
        OVERPASS_URL,
        data=urllib.parse.urlencode({"data": query}).encode(),
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def join_rings(ways):
    """Spaja delove (liste tačaka) u zatvorene prstenove; nezatvoreni ostaci se odbacuju."""
    parts = [list(w) for w in ways if len(w) >= 2]
    rings = []
    while parts:
        ring = parts.pop()
        while ring[0] != ring[-1]:
            for i, part in enumerate(parts):
                if part[0] == ring[-1]:
                    ring += part[1:]
                elif part[-1] == ring[-1]:
                    ring += part[-2::-1]
                else:
                    continue
                del parts[i]
                break
            else:
                break  # ne zatvara se
        if ring[0] == ring[-1] and len(ring) >= 4:
            rings.append([to_xy(lat, lon) for lat, lon in ring[:-1]])
    return rings


def area(ring):
    return abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]))) / 2


def polygons_of(element):
    """Obris kao lista (spoljni prsten, [dvorišta]); relacija sme da ima više delova po ulozi
    i više odvojenih spoljnih prstenova. Prstenovi su bez ponovljene poslednje tačke."""
    def points(geometry):
        return [(p["lat"], p["lon"]) for p in geometry]

    if element["type"] == "way":
        return [(r, []) for r in join_rings([points(element["geometry"])])]
    members = [m for m in element["members"] if m.get("geometry")]
    outers = join_rings([points(m["geometry"]) for m in members if m.get("role") == "outer"])
    inners = join_rings([points(m["geometry"]) for m in members if m.get("role") == "inner"])
    polygons = [(outer, []) for outer in outers]
    for inner in inners:
        host = next((p for p in polygons if inside(p[0], *inner[0])), None)
        if host:
            host[1].append(inner)
    return polygons


def ring_of(element):
    """Spoljni prsten najvećeg dela obrisa (bez ponovljene poslednje tačke), ili None."""
    polygon = main_polygon(element)
    return polygon[0] if polygon else None


def main_polygon(element):
    return max(polygons_of(element), key=lambda p: area(p[0]), default=None)


def outline_of(bid, element):
    """Najveći deo obrisa zgrade [bid]; bez dvorišta ako je u NO_HOLES."""
    ring, holes = main_polygon(element)
    return ring, [] if bid in NO_HOLES else holes


def centroid(ring):
    a = cx = cy = 0.0
    for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]):
        c = x1 * y2 - x2 * y1
        a += c
        cx += (x1 + x2) * c
        cy += (y1 + y2) * c
    return cx / (3 * a), cy / (3 * a)


def inside(ring, x, y):
    result = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]):
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            result = not result
    return result


def interior_point(ring):
    """Težište, ili (za udubljene obrise, npr. češalj Mašinskog instituta) sredina najdužeg
    preseka kroz težište koji je unutar zgrade."""
    cx, cy = centroid(ring)
    if inside(ring, cx, cy):
        return cx, cy
    xs = sorted(
        x1 + (cy - y1) * (x2 - x1) / (y2 - y1)
        for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1])
        if (y1 > cy) != (y2 > cy)
    )
    a, b = max(zip(xs[::2], xs[1::2]), key=lambda s: s[1] - s[0])
    return (a + b) / 2, cy


def project(px, py, ax, ay, bx, by):
    """Najbliža tačka duži AB tački P i parametar t (0..1)."""
    dx, dy = bx - ax, by - ay
    t = 0.0 if dx == dy == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return ax + t * dx, ay + t * dy, t


def snap_to_wall(ring, x, y):
    return min(
        (project(x, y, *a, *b)[:2] for a, b in zip(ring, ring[1:] + ring[:1])),
        key=lambda p: math.dist(p, (x, y)),
    )


def fit_similarity(src, dst):
    """Rotacija + uniformna skala + pomeraj (bez ogledanja), najmanji kvadrati."""
    n = len(src)
    sc = complex(sum(p[0] for p in src) / n, sum(p[1] for p in src) / n)
    dc = complex(sum(p[0] for p in dst) / n, sum(p[1] for p in dst) / n)
    num = sum((complex(*q) - dc) * (complex(*p) - sc).conjugate() for p, q in zip(src, dst))
    den = sum(abs(complex(*p) - sc) ** 2 for p in src)
    z = num / den
    return lambda p: ((z * (complex(*p) - sc) + dc).real, (z * (complex(*p) - sc) + dc).imag), z


def nb_plan_placement(ring):
    """Smešta plan u obris zgrade: uglovi spoljnog zida plana -> krajnji uglovi obrisa."""
    # Osa zgrade iz najduže ivice obrisa; u = duž zgrade ka severu (desno na planu),
    # v = poprečno ka istoku (dole na planu).
    (ax, ay), (bx, by) = max(zip(ring, ring[1:] + ring[:1]), key=lambda e: math.dist(*e))
    ux, uy = bx - ax, by - ay
    if uy > 0:  # y raste ka jugu - okreni ka severu
        ux, uy = -ux, -uy
    length = math.hypot(ux, uy)
    ux, uy = ux / length, uy / length
    vx, vy = -uy, ux  # u zarotiran za 90° u smeru kazaljke (y naniže)

    def corner(su, sv):
        return max(ring, key=lambda p: su * (p[0] * ux + p[1] * uy) + sv * (p[0] * vx + p[1] * vy))

    l, t, r, b = NB_PLAN_WALL
    src = [(r, t), (r, b), (l, b), (l, t)]
    dst = [corner(1, -1), corner(1, 1), corner(-1, 1), corner(-1, -1)]
    transform, z = fit_similarity(src, dst)
    residual = max(math.dist(transform(p), q) for p, q in zip(src, dst))
    vx0, vy0, vw, vh = NB_PLAN_VIEWPORT
    origin = transform((vx0, vy0))
    return transform, {
        "originX": round(origin[0], 2),
        "originY": round(origin[1], 2),
        "rotationDeg": round(math.degrees(math.atan2(z.imag, z.real)), 2),
        "widthM": round(abs(z) * vw, 2),
        "heightM": round(abs(z) * vh, 2),
    }, abs(z), residual


def shared_placement(to_campus, nb_placement, m_per_px, viewport):
    """Smeštaj plana u zajedničkom sistemu sa NB (tools/zgrade): ista rotacija i razmera, svoj viewport."""
    vx, vy, vw, vh = viewport
    origin = to_campus((vx, vy))
    return {
        "originX": round(origin[0], 2),
        "originY": round(origin[1], 2),
        "rotationDeg": nb_placement["rotationDeg"],
        "widthM": round(m_per_px * vw, 2),
        "heightM": round(m_per_px * vh, 2),
    }


def f_placement(to_campus, nb_placement, m_per_px):
    """F-blok je u koordinatama evakuacionog plana (build_f.py: dole sever) - zajednički sistem zarotiran za 90°:
    tačka F (x, y) je u zajedničkom sistemu build_f.to_shared, pa je rotacija smeštaja rotacija NB - 90°."""
    vx, vy, vw, vh = build_f.VIEWPORT
    origin = to_campus(build_f.to_shared((vx, vy)))
    return {
        "originX": round(origin[0], 2),
        "originY": round(origin[1], 2),
        "rotationDeg": round(nb_placement["rotationDeg"] - 90, 2),
        "widthM": round(m_per_px * vw, 2),
        "heightM": round(m_per_px * vh, 2),
    }


def ntp_plan_placement(ring):
    """Plan NTP-a u kampus (build_ntp.plan_transform): transformacija i smeštaj za campus.json."""
    transform, _, z = build_ntp.plan_transform(ring)
    vx, vy, vw, vh = build_ntp.VX, build_ntp.VY, build_ntp.VW, build_ntp.VH
    origin = transform((vx, vy))
    return transform, {
        "originX": round(origin[0], 2),
        "originY": round(origin[1], 2),
        "rotationDeg": round(math.degrees(math.atan2(z.imag, z.real)), 2),
        "widthM": round(abs(z) * vw, 2),
        "heightM": round(abs(z) * vh, 2),
    }


class Graph:
    def __init__(self):
        self.nodes = {}  # id -> (x, y, tip)
        self.adj = {}  # id -> set(id)

    def add_node(self, node_id, x, y, kind="STAZA"):
        self.nodes.setdefault(node_id, (x, y, kind))
        self.adj.setdefault(node_id, set())

    def add_edge(self, a, b):
        if a != b:
            self.adj[a].add(b)
            self.adj[b].add(a)

    def remove_edge(self, a, b):
        self.adj[a].discard(b)
        self.adj[b].discard(a)

    def xy(self, node_id):
        return self.nodes[node_id][:2]

    def edges(self):
        return sorted({tuple(sorted((a, b))) for a in self.adj for b in self.adj[a]})

    def snap(self, x, y, new_id):
        """Najbliža tačka mreže staza; ako je na sredini ivice, ivica se deli novim čvorom."""
        best = None
        for a, b in self.edges():
            if self.nodes[a][2] != "STAZA" or self.nodes[b][2] != "STAZA":
                continue
            px, py, t = project(x, y, *self.xy(a), *self.xy(b))
            d = math.dist((px, py), (x, y))
            if best is None or d < best[0]:
                best = (d, a, b, px, py, t)
        d, a, b, px, py, t = best
        length = math.dist(self.xy(a), self.xy(b))
        if t * length < 1.0:
            return a, d
        if (1 - t) * length < 1.0:
            return b, d
        self.add_node(new_id, px, py)
        self.remove_edge(a, b)
        self.add_edge(a, new_id)
        self.add_edge(new_id, b)
        return new_id, d

    def component(self, start):
        seen, stack = {start}, [start]
        while stack:
            for n in self.adj[stack.pop()]:
                if n not in seen:
                    seen.add(n)
                    stack.append(n)
        return seen

    def simplify(self, keep, tolerance=0.3):
        """Uklanja skoro kolinearne čvorove staza stepena 2 (skraćenje puta < tolerance m)."""
        changed = True
        while changed:
            changed = False
            for n in list(self.nodes):
                if n in keep or self.nodes[n][2] != "STAZA" or len(self.adj[n]) != 2:
                    continue
                a, b = self.adj[n]
                if b in self.adj[a]:
                    continue
                detour = math.dist(self.xy(a), self.xy(n)) + math.dist(self.xy(n), self.xy(b)) - math.dist(self.xy(a), self.xy(b))
                if detour < tolerance:
                    self.remove_edge(a, n)
                    self.remove_edge(n, b)
                    self.add_edge(a, b)
                    del self.nodes[n], self.adj[n]
                    changed = True


def in_area(x, y):
    return 0 <= x <= X_MAX - X_MIN and 0 <= y <= Y_MAX - Y_MIN


def r1(v):
    return round(v, 1)


def polyline(element):
    return [[r1(x), r1(y)] for x, y in (to_xy(p["lat"], p["lon"]) for p in element["geometry"])]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-o", "--output", required=True, type=Path)
    parser.add_argument("--osm", type=Path, help="snimak Overpass odgovora (čita se ako postoji, inače se pravi)")
    args = parser.parse_args()

    if args.osm and args.osm.exists():
        osm = json.loads(args.osm.read_text(encoding="utf-8"))
    else:
        osm = fetch_osm()
        if args.osm:
            args.osm.write_text(json.dumps(osm, ensure_ascii=False), encoding="utf-8")
    elements = {(e["type"], e["id"]): e for e in osm["elements"]}
    warnings = []

    # --- Zgrade ---
    def points(ring):
        return [[r1(x), r1(y)] for x, y in ring]

    rings = {}  # id zgrade/službe -> spoljni prsten (za lepljenje ulaza na zid)
    anchors = {}  # id -> tačka natpisa i čvora ZGRADA
    buildings = []
    for bid, name, label, kind, osm_id in BUILDINGS:
        ring, holes = outline_of(bid, elements[(kind, osm_id)])
        rings[bid] = ring
        anchors[bid] = interior_point(ring)
        buildings.append({
            "id": bid, "name": name, "label": label, "labelAt": [r1(v) for v in anchors[bid]],
            "outline": points(ring), **({"holes": [points(h) for h in holes]} if holes else {}),
        })
    # Službe: obris zgrade se crta jednom; služba koja je samo deo zgrade nema svoj obris. Obrisi FTN zgrada
    # (Pošta je u Amfiteatrima) su već nacrtani kao zgrade.
    drawn = {(kind, osm_id) for *_, kind, osm_id in BUILDINGS}
    for sid, name, label, kind, osm_id, at in SERVICES:
        ring, holes = outline_of(sid, elements[(kind, osm_id)])
        rings[sid] = ring
        anchors[sid] = to_xy(*at) if at else interior_point(ring)
        if not inside(ring, *anchors[sid]):
            warnings.append(f"služba {sid}: tačka je van obrisa zgrade")
        service = {"id": sid, "name": name, "label": label, "labelAt": [r1(v) for v in anchors[sid]], "category": "SLUZBA"}
        if sid in LABEL_SIDE:
            service["labelSide"] = LABEL_SIDE[sid]
        outline = {"outline": points(ring), **({"holes": [points(h) for h in holes]} if holes else {})}
        if at is None:
            service.update(outline)
        elif (kind, osm_id) not in drawn:
            buildings.append({"id": f"{kind}-{osm_id}", "category": "SLUZBA", **outline})
        drawn.add((kind, osm_id))
        buildings.append(service)
    passage_rings = {}
    for a, b, osm_id in PASSAGES:
        ring = ring_of(elements[("way", osm_id)])
        passage_rings[(a, b)] = ring
        buildings.append({"id": f"{a}-{b}", "outline": points(ring)})
    used = (
        {(kind, osm_id) for *_, kind, osm_id in BUILDINGS} | {("way", osm_id) for *_, osm_id in PASSAGES}
        | {(kind, osm_id) for _, _, _, kind, osm_id, _ in SERVICES}
    )
    # Okolne zgrade: svaki deo obrisa je lista prstenova [spoljni, dvorišta...].
    context = []
    for key, e in elements.items():
        if key in used or "building" not in e.get("tags", {}):
            continue
        for ring, holes in polygons_of(e):
            if any(in_area(x, y) for x, y in ring):
                context.append([points(ring)] + [points(h) for h in holes])

    # --- Mreža staza ---
    graph = Graph()
    for e in elements.values():
        tags = e.get("tags", {})
        if e["type"] != "way" or tags.get("highway") not in WALKABLE or tags.get("foot") == "no":
            continue
        ids = e["nodes"]
        pts = [to_xy(p["lat"], p["lon"]) for p in e["geometry"]]
        for (i1, p1), (i2, p2) in zip(zip(ids, pts), zip(ids[1:], pts[1:])):
            if in_area(*p1) and in_area(*p2):
                graph.add_node(f"K-{i1}", *p1)
                graph.add_node(f"K-{i2}", *p2)
                graph.add_edge(f"K-{i1}", f"K-{i2}")

    # --- Ulazi, zgrade, prolazi ---
    checks = {}
    special = set()
    split_count = 0

    def add_entrance(entrance, x, y):
        """Čvor ULAZ povezan sa najbližom stazom (staza se deli na tom mestu)."""
        nonlocal split_count
        graph.add_node(entrance, x, y, kind="ULAZ")
        special.add(entrance)
        split_count += 1
        attach, distance = graph.snap(x, y, f"K-X{split_count}")
        special.add(attach)
        graph.add_edge(entrance, attach)
        if distance > 15:
            warnings.append(f"ulaz {entrance}: najbliža staza je {distance:.0f} m daleko")

    ntp_to_campus, ntp_placement = ntp_plan_placement(rings["NTP"])
    for bid, entrances in ENTRANCES.items():
        building_node = None if bid in WITH_INTERIOR else f"K-Z-{bid}"
        if building_node:
            graph.add_node(building_node, *anchors[bid], kind="ZGRADA")
            special.add(building_node)
        for i, spec in enumerate(entrances, start=1):
            if isinstance(spec, int):
                node = elements[("node", spec)]
                x, y = to_xy(node["lat"], node["lon"])
            elif spec[0] == "plan":
                x, y = ntp_to_campus(spec[1:])
                wall = math.dist((x, y), snap_to_wall(rings[bid], x, y))
                checks[f"ulaz K-U-{bid}-{i} {spec[1:]} -> OSM zid"] = wall
            else:
                x, y = snap_to_wall(rings[bid], *to_xy(*spec))
            entrance = f"K-U-{bid}-{i}"
            add_entrance(entrance, x, y)
            if building_node:
                graph.add_edge(building_node, entrance)
    for (a, b), ring in passage_rings.items():
        passage = f"K-P-{a}-{b}"
        graph.add_node(passage, *centroid(ring), kind="PROLAZ")
        special.add(passage)
        for side in (a, b):
            if side not in WITH_INTERIOR:
                graph.add_edge(passage, f"K-Z-{side}")

    # Samo deo mreže povezan sa ulazima (ostatak je van dohvata).
    main_component = graph.component("K-U-NB-1")
    for node in special:
        if node not in main_component and graph.nodes[node][2] != "PROLAZ":
            warnings.append(f"{node} nije povezan sa glavnim ulazom Nastavnog bloka")
    for node in list(graph.nodes):
        if node not in main_component and node not in special:
            del graph.nodes[node], graph.adj[node]
    graph.simplify(keep=special)

    # --- Plan Nastavnog bloka u koordinatama kampusa ---
    to_campus, placement, m_per_px, residual = nb_plan_placement(rings["NB"])
    checks.update({
        "glavni ulaz -> OSM ulaz": math.dist(to_campus(build_nb.ENTRANCE), graph.xy("K-U-NB-1")),
        "spojni prolaz -> prolaz NB-KULA": math.dist(to_campus(build_nb.PASSAGE_KULA), graph.xy("K-P-NB-KULA")),
        "prolaz ka Amfiteatrima -> prolaz AMF-NB": math.dist(to_campus(build_nb.PASSAGE_AMF), graph.xy("K-P-AMF-NB")),
    })
    # Amfiteatri i Kula su u istom sistemu kao plan NB (tools/zgrade/common.py) - ista rotacija i razmera.
    shared = {bid: shared_placement(to_campus, placement, m_per_px, module.VIEWPORT)
              for bid, module in (("AMF", build_amf), ("KULA", build_kula))}
    shared["F"] = f_placement(to_campus, placement, m_per_px)
    # MI (03.10.2026): plan je u zajedničkom sistemu (build_mi.py: OSM obris preveden u px plana NB).
    shared["MI"] = shared_placement(to_campus, placement, m_per_px, build_mi.VIEWPORT)
    for label, (plan_xy, node) in {
        "AMF ulaz": ((245, -65), "K-U-AMF-1"), "AMF ulaz GRID": ((723, -65), "K-U-AMF-2"),
        "AMF prolaz ka NB": ((680, 207), "K-P-AMF-NB"), "AMF prolaz ka Kuli": ((257, 210), "K-P-AMF-KULA"),
        "AMF prolaz ka F": ((40, -13), "K-P-AMF-F"), "AMF prolaz ka ITC": ((832, -10), "K-P-ITC-AMF"),
        "Kula ulaz": ((243, 554), "K-U-KULA-1"), "Kula prolaz ka NB": ((333, 413), "K-P-NB-KULA"),
        "Kula trem ka AMF": ((236, 376), "K-P-AMF-KULA"),
        "F pasarela ka AMF": (build_f.to_shared(build_f.PASSAGE), "K-P-AMF-F"),
        "MI ulaz": (build_mi.ENTRANCE, "K-U-MI-1"),
    }.items():
        checks[f"{label} -> {node}"] = math.dist(to_campus(plan_xy), graph.xy(node))

    # --- Crtež: ulice i staze ---
    streets, paths = [], []
    for e in elements.values():
        highway = e.get("tags", {}).get("highway")
        if e["type"] != "way" or highway is None or e.get("tags", {}).get("area") == "yes":
            continue
        line = polyline(e)
        if not any(in_area(x, y) for x, y in line):
            continue
        if highway in ROADS:
            streets.append(line)
        elif highway in FOOTPATHS:
            paths.append(line)

    data = {
        "attribution": "© OpenStreetMap contributors",
        "generated": dt.date.today().isoformat(),
        "widthM": X_MAX - X_MIN,
        "heightM": Y_MAX - Y_MIN,
        "plans": {"NB": placement, **shared, "NTP": ntp_placement},
        "buildings": buildings,
        "context": context,
        "streets": streets,
        "paths": paths,
        "nodes": [{"id": n, "x": r1(x), "y": r1(y), "type": t} for n, (x, y, t) in sorted(graph.nodes.items())],
        "edges": [list(e) for e in graph.edges()],
    }
    args.output.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    print(f"zgrade: {len(buildings)} (+{len(context)} okolnih), ulice: {len(streets)}, staze: {len(paths)}")
    print(f"graf: {len(data['nodes'])} čvorova, {len(data['edges'])} ivica")
    print(f"plan NB: {m_per_px:.4f} m/px, {placement}, odstupanje uglova {residual:.2f} m")
    print(f"plan NTP: {build_ntp.M_PER_PX} m/px, {ntp_placement}")
    for label, d in checks.items():
        print(f"  provera {label}: {d:.1f} m")
    for w in warnings:
        print("UPOZORENJE:", w, file=sys.stderr)


if __name__ == "__main__":
    main()
