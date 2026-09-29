"""Smirivanje posle premeštanja telefona: koliko je pravac telefona u trenutku smirivanja daleko od
pravca kad se telefon zaista ustali (kružna sredina 2-4 s posle smirivanja).

  python tools/pdr/settle.py pdr/hod-....csv [...]

Prepoznavanje kao WalkingDirection (brzo izglađeno "gore" > 45° za 1,5 s). Pravila smirivanja:
  sporo 0,5 s  - sporo izglađeno "gore" (τ ≈ 1 s) za 0,5 s < 12° (kao aplikacija do 29.09.)
  sporo 1 s    - isto, za 1 s
  brzo 1 s     - brzo izglađeno "gore" (τ ≈ 0,4 s) za 1 s < 15°
Posle smirivanja i pravac telefona posle N koraka (zbir pravca od smirivanja do N-tog koraka).
"""
import math
import sys

FAST, SLOW, SMOOTH = 0.05, 0.02, 0.1


def ang(a, b):
    d = sum(x * y for x, y in zip(a, b)); n = math.sqrt(sum(x * x for x in a) * sum(x * x for x in b))
    return math.degrees(math.acos(max(-1, min(1, d / n))))


def adiff(a, b):
    return (a - b + 540) % 360 - 180


def cmean(degs):
    e = sum(math.sin(math.radians(d)) for d in degs); n = sum(math.cos(math.radians(d)) for d in degs)
    return math.degrees(math.atan2(e, n)) % 360


def load(path):
    rows = []; steps = []; t0 = None
    fast = slow = None; se = sn = None
    for l in open(path):
        f = l.split(',')
        if f[0] not in ('R', 'S'):
            continue
        t = int(f[1]); t0 = t0 or t; s = (t - t0) / 1e9
        if f[0] == 'S':
            steps.append(s); continue
        m = [float(x) for x in f[2:11]]
        up = m[6:9]; e = m[1] - m[2]; n = m[4] - m[5]
        if fast is None:
            fast, slow, se, sn = list(up), list(up), e, n
        else:
            fast = [a + FAST * (b - a) for a, b in zip(fast, up)]
            slow = [a + SLOW * (b - a) for a, b in zip(slow, up)]
            se += SMOOTH * (e - se); sn += SMOOTH * (n - sn)
        rows.append((s, list(fast), list(slow), e, n, math.degrees(math.atan2(se, sn)) % 360))
    return rows, steps


def at(rows, s):
    return min(rows, key=lambda r: abs(r[0] - s))


def main(path):
    rows, steps = load(path)
    print("==", path)
    i = 0; busy_until = -1
    while i < len(rows):
        s = rows[i][0]
        if s < busy_until:
            i += 1; continue
        ref = at(rows, s - 1.5)
        if s > 1.5 and ang(rows[i][1], ref[1]) > 45:
            print(f"  prepoznato {s:6.1f} s")
            rules = {
                'sporo 0,5 s': (2, 0.5, 12), 'sporo 1 s': (2, 1.0, 12), 'brzo 1 s': (1, 1.0, 15),
            }
            for name, (col, win, th) in rules.items():
                t = s + 0.5
                while t < s + 4.0 and ang(at(rows, t)[col], at(rows, t - win)[col]) >= th:
                    t += 0.5
                t = min(t, s + 4.0)
                final = cmean([r[5] for r in rows if t + 2 <= r[0] <= t + 4])
                now = at(rows, t)[5]
                after = []
                for nsteps in (2, 3, 4):
                    st = [x for x in steps if x > t][:nsteps]
                    if len(st) < nsteps:
                        after.append('   -'); continue
                    ws = [r for r in rows if t <= r[0] <= st[-1]]
                    ph = math.degrees(math.atan2(sum(r[3] for r in ws), sum(r[4] for r in ws))) % 360
                    after.append(f"{adiff(ph, final):+4.0f}")
                print(f"    {name:11s}: smireno {t:6.1f} s ({t - s:3.1f} s), pravac tada {adiff(now, final):+4.0f}° od ustaljenog;"
                      f" posle 2/3/4 koraka {'/'.join(after)}°")
            busy_until = s + 6
        i += 1


if __name__ == '__main__':
    for p in sys.argv[1:]:
        main(p)
