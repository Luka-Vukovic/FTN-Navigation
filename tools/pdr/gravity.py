"""Gravitacija ("gore") u koordinatama telefona kroz vreme - premeštanje telefona (ruka -> džep).

  python tools/pdr/gravity.py pdr/hod-....csv [korak_s]

Kao WalkingDirection: "gore" = treći red matrice rotacije, brzo izglađen (UP_FAST_ALPHA 0,05).
Kolone: t, nagib (ugao ekrana od horizontale), "gore" (x, y, z telefona), okret brzo izglađenog
"gore" od reference poslednjeg koraka (kao u aplikaciji - upRef se osvežava na svaki korak), od
stanja pre 2 s i od početka snimka, i broj koraka u intervalu.
"""
import math
import sys

UP_FAST_ALPHA = 0.05


def angle(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    return math.degrees(math.acos(max(-1.0, min(1.0, dot / (na * nb)))))


def main(path, every):
    up = None
    ref = None
    first = None
    t0 = None
    hist = []  # (t, up) za "pre 2 s"
    next_t = 0.0
    steps = 0
    max_since_step = 0.0
    print(f"{'t':>6} {'nagib':>5} {'gore x':>6} {'y':>5} {'z':>5} {'od koraka':>9} {'max':>5} {'od 2 s':>6} {'od poč.':>7} kor")
    for line in open(path):
        f = line.rstrip().split(',')
        if len(f) < 2:
            continue
        t = int(f[1])
        if t0 is None:
            t0 = t
        sec = (t - t0) / 1e9
        if f[0] == 'R':
            m = [float(x) for x in f[2:11]]
            raw = m[6:9]
            if up is None:
                up = list(raw)
                ref = list(up)
                first = list(up)
            else:
                up = [u + UP_FAST_ALPHA * (r - u) for u, r in zip(up, raw)]
            max_since_step = max(max_since_step, angle(up, ref))
            hist.append((sec, list(up)))
            while hist and sec - hist[0][0] > 2.0:
                hist.pop(0)
            if sec >= next_t:
                tilt = math.degrees(math.acos(max(-1.0, min(1.0, raw[2]))))
                print(f"{sec:6.1f} {tilt:5.0f} {up[0]:6.2f} {up[1]:5.2f} {up[2]:5.2f} {angle(up, ref):9.0f} "
                      f"{max_since_step:5.0f} {angle(up, hist[0][1]):6.0f} {angle(up, first):7.0f} {steps:3d}")
                steps = 0
                next_t += every
        elif f[0] == 'S' and up is not None:
            steps += 1
            ref = list(up)
            max_since_step = 0.0
        elif f[0] in ('P', 'C'):
            print(f"{sec:6.1f}  --- {'pauza' if f[0] == 'P' else 'nastavak'} ---")


if __name__ == '__main__':
    main(sys.argv[1], float(sys.argv[2]) if len(sys.argv) > 2 else 1.0)
