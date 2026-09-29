"""Analiza snimka PDR senzora (SensorRecorder CSV) po koraku - samo stdlib.

  python tools/pdr/analyze.py pdr/hod-....csv

Kolone: t, dt (od prethodnog koraka), nagib (ugao ekrana od horizontale: 0 = leži ekranom
gore, 90 = uspravno, 180 = ekranom dole), azimut gornje ivice (Y), kamere (-Z), pravca
telefona kao u WalkingDirection (Y - Z), osa hoda (PCA horizontalnog ubrzanja u koraku,
bez znaka) + odnos sporedne/glavne varijanse, smer koji je aplikacija dala.
"""
import math
import sys


def az(e, n):
    return math.degrees(math.atan2(e, n)) % 360


def main(path):
    rows = [l.rstrip('\n').split(',') for l in open(path)]
    R = None
    t0 = None
    acc = []  # (t, e, n) od prethodnog koraka
    rots = []  # (Ye, Yn, -Ze, -Zn, tilt)
    last_step = None
    print(f"{'t':>7} {'dt':>5} {'nagib':>5} {'Y':>5} {'kam':>5} {'tel':>5} {'osa':>5} {'odn':>5} {'var':>5} {'smer':>5}")
    for f in rows:
        if len(f) < 2:
            continue
        kind, t = f[0], int(f[1])
        if t0 is None:
            t0 = t
        if kind == 'R':
            R = [float(x) for x in f[2:11]]
            # kolone: X=(R0,R3,R6), Y=(R1,R4,R7), Z=(R2,R5,R8) u (istok, sever, gore)
            tilt = math.degrees(math.acos(max(-1, min(1, R[8]))))
            rots.append((R[1], R[4], -R[2], -R[5], tilt))
        elif kind == 'A' and R:
            x, y, z = (float(v) for v in f[2:5])
            e = R[0] * x + R[1] * y + R[2] * z
            n = R[3] * x + R[4] * y + R[5] * z
            acc.append((t, e, n))
        elif kind == 'S':
            dt = (t - last_step) / 1e9 if last_step else 0
            last_step = t
            if rots:
                ye = sum(r[0] for r in rots); yn = sum(r[1] for r in rots)
                ce = sum(r[2] for r in rots); cn = sum(r[3] for r in rots)
                tilt = sum(r[4] for r in rots) / len(rots)
                ya, ca, pa = az(ye, yn), az(ce, cn), az(ye + ce, yn + cn)
            else:
                tilt = ya = ca = pa = float('nan')
            axis = ratio = var = float('nan')
            if len(acc) >= 10:
                me = sum(a[1] for a in acc) / len(acc); mn = sum(a[2] for a in acc) / len(acc)
                see = sum((a[1] - me) ** 2 for a in acc) / len(acc)
                snn = sum((a[2] - mn) ** 2 for a in acc) / len(acc)
                sen = sum((a[1] - me) * (a[2] - mn) for a in acc) / len(acc)
                half = (see + snn) / 2
                spread = math.sqrt(((see - snn) / 2) ** 2 + sen ** 2)
                major, minor = half + spread, half - spread
                ang = 0.5 * math.atan2(2 * sen, see - snn)
                axis = az(math.cos(ang), math.sin(ang)) % 180
                ratio = minor / major if major > 0 else float('nan')
                var = major
            print(f"{(t - t0) / 1e9:7.2f} {dt:5.2f} {tilt:5.0f} {ya:5.0f} {ca:5.0f} {pa:5.0f} {axis:5.0f} {ratio:5.2f} {var:5.2f} {float(f[2]):5.0f}")
            acc.clear(); rots.clear()
        elif kind in 'PC':
            print(f"{(t - t0) / 1e9:7.2f}  --- {'pauza' if kind == 'P' else 'nastavak'} ---")


if __name__ == '__main__':
    main(sys.argv[1])
