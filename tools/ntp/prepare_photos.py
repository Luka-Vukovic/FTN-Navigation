"""
Priprema fotografija evakuacionih planova NTP-a za precrtavanje i proveru (build_ntp.py --check).

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/ntp/prepare_photos.py <izlazni folder>

Ulaz: images/ntp0.jpg ... ntp5.jpg (prizemlje ... V sprat; van gita). Izlaz: w0.png ... w5.png -
svaki sprat u zajedničkom sistemu (px ispravljenog II sprata), platno x -300..2140, y 0..1870.

1. Perspektiva: spoljni (debeli) okvir plana (CORNERS, px originalne fotografije, očitano sa uvećanih
   isečaka) -> pravougaonik 3000 x 2121. Odnos √2 (papir A formata): sa pretpostavljenih 3:2 prsten
   simbola "zborno mesto" je bio 6 % širi nego viši.
2. Poravnanje sa II spratom (REG): x2 = s x + tx, y2 = s y + ty. Nađeno pretragom najvećeg preklopa
   tamnih linija (razmera 0,80-1,20, pomeraj; od grubog ka finom), provereno preklopom u dve boje:
   stepeništa i liftovi se poklapaju na svim spratovima. Prizemlje je na planu nacrtano sitnije (s 1,173).
"""

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
W, H = 3000, 2121

CORNERS = {  # TL, TR, BL, BR spoljnog okvira, px fotografije
    0: [(263, 767), (3038, 826), (334, 2513), (2796, 2675)],
    1: [(179, 346), (4007, 437), (276, 2883), (3821, 2908)],
    2: [(583, 673), (3798, 699), (667, 2743), (3648, 2802)],
    3: [(57, 265), (4030, 325), (277, 2823), (3777, 2809)],
    4: [(326, 350), (3952, 364), (520, 2770), (3794, 2641)],
    5: [(168, 177), (3943, 264), (349, 2718), (3764, 2623)],
}

K = H / 2000  # pomeraji su nađeni na slikama visine 2000 (3:2), pre ispravke odnosa
REG = {0: (1.173, -398.0, -257.9 * K), 1: (0.98, 14.0, 20.0 * K), 2: (1.0, 0.0, 0.0),
       3: (0.985, 12.0, 25.2 * K), 4: (0.992, -12.0, 6.2 * K), 5: (0.983, 7.0, 19.6 * K)}
CANVAS_X0, CANVAS_W, CANVAS_H = -300, 2440, 1870


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
    """Koeficijenti za PIL PERSPECTIVE: tačka izlaza (u, v) -> tačka fotografije."""
    a, b = [], []
    for (x, y), (u, v) in zip(src, dst):
        a.append([u, v, 1, 0, 0, 0, -u * x, -v * x])
        b.append(x)
        a.append([0, 0, 0, u, v, 1, -u * y, -v * y])
        b.append(y)
    return solve(a, b)


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for f, src in CORNERS.items():
        photo = Image.open(ROOT / f"images/ntp{f}.jpg").convert("RGB")
        flat = photo.transform((W, H), Image.PERSPECTIVE, homography(src, [(0, 0), (W, 0), (0, H), (W, H)]), Image.BICUBIC)
        s, tx, ty = REG[f]
        flat.transform(
            (CANVAS_W, CANVAS_H), Image.AFFINE, (1 / s, 0, (CANVAS_X0 - tx) / s, 0, 1 / s, -ty / s),
            Image.BICUBIC, fillcolor=(255, 255, 255),
        ).save(out / f"w{f}.png")
        print(f"sprat {f}: {out / f'w{f}.png'}")


if __name__ == "__main__":
    main()
