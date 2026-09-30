"""
Priprema snimaka ekrana veb aplikacije FtnGO (v0.8.4) za precrtavanje zgrada (build_nb.py, build_amf.py,
build_kula.py).

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/zgrade/prepare_screens.py <nb|amf|kula> <izlazni folder>

Ulaz (images/, van gita) - 3D pogled pod uglom, isti smer kao naš plan (gore = zapad, dole = istok,
levo = jug, desno = sever): nb-1.png, nb0..nb5.png; amf-1.png, amf0.png, amf1.png; kula0..kula9.png. Izlaz:
w<sprat>.png - svaki sprat u koordinatama plana (common.py: zajednički sistem NB, AMF i Kule, px plana,
1 px = M_PER_PX m), platno = viewport zgrade uvećan ZOOM puta.

Perspektiva: spoljni uglovi zgrade na svakom snimku (CORNERS, vrhovi zidova, očitano sa uvećanih
isečaka) -> spoljni zid zgrade u planu (WALL = OSM obris, u sistemu plana pravougaonik).

NB, V sprat: pokriva samo srednji pojas zgrade, pa njegovi uglovi nisu uglovi zgrade. Viša ravan iste
kamere izgleda kao niža sa planom uniformno uvećanim oko tačke ispod kamere: H5(p) = H4(k p + c). Snimci
nb4 i nb5 su iz iste kamere. k i x te tačke su iz krajnjih zidova ploče V sprata (ARH CRT uz južni, AH9 uz
severni zid); y tačke nije merljiv na nb5, pa je uzet sa spratova 0 -> 3 (ista kamera, nb0-nb3):
NB_FIXED_Y (x je tamo 727, ovde 732 - kamere su slične). Greška od 50 px u NB_FIXED_Y pomera ploču za ~5 px.

AMF: -1 je uži od prizemlja samo zbog perspektive (niža ravan); levo od zida ide Skriptarnica ka F-bloku.
AMF 1 u FtnGO-u je skoro isti crtež kao prizemlje (gornji deo amfiteatara); crta se samo L1.
Kula: -1 je prazna prostorija (samo stepenište) i ne crta se.
"""

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_amf  # noqa: E402
import build_kula  # noqa: E402
import build_nb  # noqa: E402

ZOOM = 3

BUILDINGS = {
    "nb": {
        "module": build_nb,
        "image": "nb{}.png",
        "corners": {  # TL, TR, BL, BR spoljnih zidova (vrh zida), px snimka
            -1: [(257, 354), (1645, 354), (167, 781), (1736, 781)],
            0: [(361, 299), (1556, 299), (290, 689), (1629, 688)],
            1: [(314, 262), (1600, 262), (231, 686), (1684, 686)],
            2: [(256, 235), (1654, 235), (159, 721), (1752, 721)],
            3: [(196, 195), (1714, 195), (79, 724), (1843, 724)],
            4: [(228, 181), (1690, 181), (118, 686), (1797, 687)],
        },
    },
    "amf": {
        "module": build_amf,
        "image": "amf{}.png",
        "corners": {
            -1: [(409, 296), (1640, 295), (323, 827), (1750, 826)],
            0: [(368, 261), (1692, 264), (270, 838), (1815, 838)],
            1: [(373, 259), (1692, 264), (271, 839), (1812, 838)],  # skoro isti kao prizemlje
        },
    },
    "kula": {
        "module": build_kula,
        "image": "kula{}.png",
        "corners": {
            0: [(797, 388), (1072, 388), (785, 694), (1083, 694)],
            1: [(789, 372), (1079, 372), (771, 698), (1090, 698)],
            2: [(774, 350), (1084, 350), (753, 725), (1100, 725)],
            3: [(762, 327), (1097, 327), (740, 730), (1113, 730)],
            4: [(748, 297), (1108, 297), (721, 732), (1125, 732)],
            5: [(730, 268), (1120, 268), (698, 736), (1143, 736)],
            6: [(686, 221), (1130, 221), (644, 760), (1163, 760)],
            7: [(658, 175), (1148, 175), (613, 764), (1188, 766)],
            8: [(684, 176), (1135, 176), (643, 710), (1164, 710)],
            9: [(660, 122), (1150, 121), (611, 712), (1190, 712)],
        },
    },
}

# NB: spoljni uglovi ploče V sprata (nb5.png), TL, TR, BL, BR.
NB_TOP_CORNERS = [(129, 279), (1785, 279), (71, 512), (1842, 512)]
NB_FIXED_Y = 476.0


def solve(a, b):
    n = len(a)
    m = [row[:] + [b[i]] for i, row in enumerate(a)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(m[r][c]))
        m[c], m[p] = m[p], m[c]
        for r in range(n):
            if r != c:
                f = m[r][c] / m[c][c]
                m[r] = [x - f * y for x, y in zip(m[r], m[c])]
    return [m[i][n] / m[i][i] for i in range(n)]


def homography(src, dst):
    """3x3 H: src -> dst (4 para tačaka), h33 = 1."""
    a, b = [], []
    for (x, y), (u, v) in zip(src, dst):
        a.append([x, y, 1, 0, 0, 0, -x * u, -y * u])
        b.append(u)
        a.append([0, 0, 0, x, y, 1, -x * v, -y * v])
        b.append(v)
    h = solve(a, b)
    return [h[0:3], h[3:6], [h[6], h[7], 1.0]]


def apply(h, p):
    x, y = p
    w = h[2][0] * x + h[2][1] * y + h[2][2]
    return ((h[0][0] * x + h[0][1] * y + h[0][2]) / w, (h[1][0] * x + h[1][1] * y + h[1][2]) / w)


def inverse(m):
    (a, b, c), (d, e, f), (g, h, i) = m
    det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    return [[(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det],
            [(f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det],
            [(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det]]


def matmul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def floor_homographies(name):
    """Sprat -> H (px plana -> px snimka)."""
    spec = BUILDINGS[name]
    l, t, r, b = spec["module"].WALL
    homs = {f: homography([(l, t), (r, t), (l, b), (r, b)], src) for f, src in spec["corners"].items()}
    if name == "nb":
        inv4 = inverse(homs[4])
        tl, tr, bl, br = (apply(inv4, p) for p in NB_TOP_CORNERS)
        k = ((tr[0] - tl[0]) + (br[0] - bl[0])) / 2 / (r - l)
        cx = (tl[0] + bl[0]) / 2 - k * l
        cy = NB_FIXED_Y * (1 - k)
        homs[5] = matmul(homs[4], [[k, 0, cx], [0, k, cy], [0, 0, 1]])
    return homs


def nb_top_slab():
    """Ploča V sprata NB u px plana: (x0, y0, x1, y1)."""
    inv = inverse(floor_homographies("nb")[5])
    tl, tr, bl, br = (apply(inv, p) for p in NB_TOP_CORNERS)
    return (tl[0] + bl[0]) / 2, (tl[1] + tr[1]) / 2, (tr[0] + br[0]) / 2, (bl[1] + br[1]) / 2


def main():
    name, out = sys.argv[1], Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    spec = BUILDINGS[name]
    vx, vy, vw, vh = spec["module"].VIEWPORT
    if name == "nb":
        print(f"NB, V sprat: ploča (px plana) = {tuple(round(v, 1) for v in nb_top_slab())}")
    for f, h in floor_homographies(name).items():
        shot = Image.open(ROOT / "images" / spec["image"].format(f)).convert("RGB")
        # PIL PERSPECTIVE: tačka izlaza -> tačka snimka; izlaz = plan uvećan ZOOM puta od (vx, vy).
        m = matmul(h, [[1 / ZOOM, 0, vx], [0, 1 / ZOOM, vy], [0, 0, 1]])
        coeffs = [v / m[2][2] for row in m for v in row][:8]
        shot.transform((vw * ZOOM, vh * ZOOM), Image.PERSPECTIVE, coeffs, Image.BICUBIC,
                       fillcolor=(255, 255, 255)).save(out / f"w{f}.png")
    print(f"izlaz: {out}")


if __name__ == "__main__":
    main()
