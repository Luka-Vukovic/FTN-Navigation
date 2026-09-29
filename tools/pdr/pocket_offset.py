"""Odstupanje (pravac hoda - pravac telefona) iz pravca faze, usrednjeno preko više koraka.

  python tools/pdr/pocket_offset.py pdr/hod-....csv [broj_koraka]

U džepu je odstupanje stalno (telefon je fiksiran na nozi), a pravac iz faze po koraku slab. Za
svaki korak: vektor korelacije (kao WalkingDirection, prozor od koraka k-2) se okrene u pravac
telefona i sabere preko poslednjih N koraka. Kolone: t, telefon, faza koraka i jačina, odstupanje
iz zbira N koraka i njegova doslednost (0..1 - dužina zbira jediničnih vektora / N).
"""
import math
import sys

sys.path.insert(0, 'tools/pdr')
from explore_sign import load


def phase_vec(w, lags=range(1, 16)):
    n = len(w)
    me = sum(s[1] for s in w) / n; mn = sum(s[2] for s in w) / n; mu = sum(s[3] for s in w) / n
    e = [s[1] - me for s in w]; nn = [s[2] - mn for s in w]; v = [s[3] - mu for s in w]
    ce = cn = 0.0
    for lag in lags:
        for i in range(n - lag):
            ce += e[i] * v[i + lag] - e[i + lag] * v[i]
            cn += nn[i] * v[i + lag] - nn[i + lag] * v[i]
    sh = math.sqrt(sum(a * a + b * b for a, b in zip(e, nn)) / n); sv = math.sqrt(sum(x * x for x in v) / n)
    st = math.hypot(ce, cn) / (n * len(lags)) / (sh * sv) if sh * sv > 0 else 0
    return math.degrees(math.atan2(ce, cn)) % 360, st


def main(path, N):
    s, steps = load(path); t0 = s[0][0]
    hist = []
    print("     t  telefon  faza  jač.  | odstupanje (N)  doslednost")
    for k, st in enumerate(steps):
        frm = max(steps[k - 2] if k >= 2 else s[0][0], st - 5_000_000_000)
        w = [x for x in s if frm <= x[0] <= st]
        if len(w) < 20:
            continue
        d, stg = phase_vec(w)
        pe = sum(math.sin(math.radians(x[4])) for x in w); pn = sum(math.cos(math.radians(x[4])) for x in w)
        p = math.degrees(math.atan2(pe, pn)) % 360
        off = math.radians(d - p)
        hist.append((math.sin(off) * stg, math.cos(off) * stg, stg))
        hist = hist[-N:]
        se = sum(h[0] for h in hist); sn = sum(h[1] for h in hist); sw = sum(h[2] for h in hist)
        mean = (math.degrees(math.atan2(se, sn)) + 180) % 360 - 180
        cons = math.hypot(se, sn) / sw if sw else 0
        print(f"{(st - t0) / 1e9:6.2f} {p:7.0f} {d:6.0f} {stg:5.2f}  | {mean:10.0f}°     {cons:5.2f}")


if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 8)
