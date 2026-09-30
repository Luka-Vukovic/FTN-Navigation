"""
Priprema snimaka ekrana veb aplikacije FtnGO (v0.8.4) za precrtavanje Nastavnog bloka (build_nb.py).

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/nb/prepare_screens.py <izlazni folder>

Ulaz: images/nb-1.png, nb0.png ... nb5.png (van gita) - 3D pogled pod uglom, isti smer kao naš plan
(gore = zapad, Amfiteatri; dole = istok, glavni ulaz; levo = jug, Kula). Izlaz: w-1.png ... w5.png -
svaki sprat u koordinatama plana NB (build_nb.py: px plana, 1 px = M_PER_PX m), platno = viewport plana
uvećan ZOOM puta.

Perspektiva: spoljni uglovi zgrade na svakom snimku (CORNERS, vrhovi zidova, očitano sa uvećanih
isečaka) -> spoljni zid plana (WALL, pravougaonik OSM obrisa 63,4 x 21,1 m).

V sprat pokriva samo srednji pojas zgrade, pa njegovi uglovi nisu uglovi zgrade. Viša ravan iste kamere
izgleda kao niža sa planom uniformno uvećanim oko tačke ispod kamere: H5(p) = H4(k p + c). Snimci nb4 i
nb5 su iz iste kamere. k i x te tačke su iz krajnjih zidova ploče V sprata (ARH CRT uz južni, AH9 uz
severni zid zgrade); y tačke nije merljiv na nb5, pa je uzet sa spratova 0 -> 3 (ista kamera, nb0-nb3):
FIXED_Y (x je tamo 727, ovde 732 - kamere su slične). Greška od 50 px u FIXED_Y pomera ploču za ~5 px.
"""

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_nb import VX, VY, VW, VH, WALL  # noqa: E402

ZOOM = 3

CORNERS = {  # TL, TR, BL, BR spoljnih zidova zgrade (vrh zida), px snimka
    -1: [(257, 354), (1645, 354), (167, 781), (1736, 781)],
    0: [(361, 299), (1556, 299), (290, 689), (1629, 688)],
    1: [(314, 262), (1600, 262), (231, 686), (1684, 686)],
    2: [(256, 235), (1654, 235), (159, 721), (1752, 721)],
    3: [(196, 195), (1714, 195), (79, 724), (1843, 724)],
    4: [(228, 181), (1690, 181), (118, 686), (1797, 687)],
}
# Spoljni uglovi ploče V sprata (nb5.png), TL, TR, BL, BR.
TOP_CORNERS = [(129, 279), (1785, 279), (71, 512), (1842, 512)]
FIXED_Y = 476.0


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


def floor_homographies():
    """Sprat -> H (px plana -> px snimka)."""
    l, t, r, b = WALL
    homs = {f: homography([(l, t), (r, t), (l, b), (r, b)], src) for f, src in CORNERS.items()}
    inv4 = inverse(homs[4])
    tl, tr, bl, br = (apply(inv4, p) for p in TOP_CORNERS)
    k = ((tr[0] - tl[0]) + (br[0] - bl[0])) / 2 / (r - l)
    cx = (tl[0] + bl[0]) / 2 - k * l
    cy = FIXED_Y * (1 - k)
    homs[5] = matmul(homs[4], [[k, 0, cx], [0, k, cy], [0, 0, 1]])
    return homs, (k, cx, cy)


def top_slab():
    """Ploča V sprata u px plana: (x0, y0, x1, y1)."""
    homs, _ = floor_homographies()
    inv = inverse(homs[5])
    tl, tr, bl, br = (apply(inv, p) for p in TOP_CORNERS)
    return (tl[0] + bl[0]) / 2, (tl[1] + tr[1]) / 2, (tr[0] + br[0]) / 2, (bl[1] + br[1]) / 2


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    homs, (k, cx, cy) = floor_homographies()
    print(f"V sprat: k = {k:.4f}, pomeraj ({cx:.1f}, {cy:.1f}); ploča (px plana) = "
          f"{tuple(round(v, 1) for v in top_slab())}")
    for f, h in homs.items():
        shot = Image.open(ROOT / f"images/nb{f}.png").convert("RGB")
        # PIL PERSPECTIVE: tačka izlaza -> tačka snimka; izlaz = plan uvećan ZOOM puta od (VX, VY).
        m = matmul(h, [[1 / ZOOM, 0, VX], [0, 1 / ZOOM, VY], [0, 0, 1]])
        coeffs = [v / m[2][2] for row in m for v in row][:8]
        shot.transform((VW * ZOOM, VH * ZOOM), Image.PERSPECTIVE, coeffs, Image.BICUBIC,
                       fillcolor=(255, 255, 255)).save(out / f"w{f}.png")
    print(f"izlaz: {out}")


if __name__ == "__main__":
    main()
