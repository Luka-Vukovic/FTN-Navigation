"""Pravac hoda iz faze po koraku, preko prozora od 2, 3 i 4 koraka: pravac/jačina."""
import math
import sys

sys.path.insert(0, 'tools/pdr')
from explore_phase import phase_dir, phone
from explore_sign import load

path = sys.argv[1]
s, steps = load(path)
t0 = s[0][0]
print(f"{'t':>6} {'tel':>4} | " + " | ".join(f"{k} kor. pravac jač" for k in (2, 3, 4)))
for i, st in enumerate(steps):
    row = []
    p = None
    for k in (2, 3, 4):
        if i < k:
            row.append(f"{'':>6} {'':>5}")
            continue
        w = [x for x in s if steps[i - k] <= x[0] <= st]
        if len(w) < 20:
            row.append(f"{'':>6} {'':>5}")
            continue
        d, strength = phase_dir(w)
        p = phone(w)
        row.append(f"{d:6.0f} {strength:5.2f}")
    print(f"{(st - t0) / 1e9:6.1f} {p if p is not None else float('nan'):4.0f} | " + " | ".join(f"{r:>17}" for r in row))
