"""Po koraku: pravac iz faze kao u WalkingDirection (prozor od koraka k-2, najviše 5 s) + jačina."""
import math, sys
sys.path.insert(0, 'tools/pdr')
from explore_sign import load
from explore_phase import phase_dir

path = sys.argv[1]
s, steps = load(path); t0 = s[0][0]
rec = [l.split(',') for l in open(path) if l.startswith('S,')]
print("     t    dt  zabel.  telefon  faza  jačina")
for k, st in enumerate(steps):
    frm = steps[k-2] if k >= 2 else s[0][0]
    frm = max(frm, st - 5_000_000_000)
    w = [x for x in s if frm <= x[0] <= st]
    if len(w) < 20: continue
    d, stg = phase_dir(w)
    pe = sum(math.sin(math.radians(x[4])) for x in w); pn = sum(math.cos(math.radians(x[4])) for x in w)
    p = math.degrees(math.atan2(pe, pn)) % 360
    dt = (st - steps[k-1]) / 1e9 if k else 0
    mark = '' if stg >= 0.35 else '  (slabo)'
    print(f"{(st-t0)/1e9:6.2f} {dt:5.2f} {float(rec[k][2]):6.0f} {p:7.0f} {d:6.0f} {stg:6.2f}{mark}")
