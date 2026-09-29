"""Ritam hoda iz vertikalnog ubrzanja (autokorelacija) naspram broja detektovanih koraka, po prozoru.

  python tools/pdr/cadence.py pdr/hod-....csv [prozor_s]

Period = prvi vrh autokorelacije između 0,35 i 1,5 s. Ako je period ~dvostruko manji od
razmaka detektovanih koraka, detektor broji svaki drugi korak.
"""
import math
import sys

sys.path.insert(0, 'tools/pdr')
from explore_sign import load


def period(v, rate):
    n = len(v)
    mean = sum(v) / n
    v = [x - mean for x in v]
    var = sum(x * x for x in v) / n
    if var < 0.05:
        return None, 0
    best, best_lag = 0, None
    lo, hi = int(0.35 * rate), int(1.5 * rate)
    prev = None
    for lag in range(lo, min(hi, n - 10)):
        c = sum(v[i] * v[i + lag] for i in range(n - lag)) / (n - lag) / var
        # prvi lokalni vrh iznad 0,3
        if prev is not None and prev[1] > c and prev[1] > 0.3 and prev[1] >= best:
            return prev[0] / rate, prev[1]
        prev = (lag, c)
    return None, 0


if __name__ == '__main__':
    path = sys.argv[1]
    win = float(sys.argv[2]) if len(sys.argv) > 2 else 5.0
    s, steps = load(path)
    t0 = s[0][0]
    rate = len(s) / ((s[-1][0] - s[0][0]) / 1e9)
    print(f"uzorkovanje ~{rate:.0f} Hz")
    print("     t  period vert. [s]  kor.  koraka detektovano  očekivano (trajanje/period)")
    i = 0
    while i < len(s):
        j = i
        while j < len(s) and s[j][0] - s[i][0] < win * 1e9:
            j += 1
        w = s[i:j]
        if len(w) > rate * 2:
            p, c = period([x[3] for x in w], rate)
            det = sum(1 for st in steps if w[0][0] <= st < w[-1][0])
            dur = (w[-1][0] - w[0][0]) / 1e9
            exp = f"{dur / p:5.1f}" if p else "    -"
            ps = f"{p:5.2f} ({c:.2f})" if p else "    -       "
            print(f"{(w[0][0] - t0) / 1e9:6.1f}  {ps:>14}  {det:5d}  {exp:>24}")
        i = j
