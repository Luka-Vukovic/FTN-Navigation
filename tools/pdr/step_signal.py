"""Signal detektora koraka (kao AccelStepDetector) i njegovi lokalni vrhovi u zadatom vremenu.

  python tools/pdr/step_signal.py pdr/hod-....csv od_s do_s

Za svaki vrh: vreme, visina (m/s² iznad bazne linije) i da li je tu detektovan korak.
Vrh ispod praga (0,6) između dva koraka = verovatno propušten korak.
"""
import math
import sys

SMOOTH, BASE, THRESHOLD = 0.25, 0.02, 0.6

path, t_from, t_to = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
t0 = None
smoothed = baseline = 9.81
sig = []
steps = []
for line in open(path):
    f = line.rstrip().split(',')
    if len(f) < 2:
        continue
    t = int(f[1])
    t0 = t if t0 is None else t0
    if f[0] == 'A':
        x, y, z = (float(v) for v in f[2:5])
        m = math.sqrt(x * x + y * y + z * z)
        smoothed += SMOOTH * (m - smoothed)
        baseline += BASE * (m - baseline)
        sig.append(((t - t0) / 1e9, smoothed - baseline))
    elif f[0] == 'S':
        steps.append((t - t0) / 1e9)

print("     t  vrh [m/s²]  korak")
for i in range(1, len(sig) - 1):
    t, v = sig[i]
    if t_from <= t <= t_to and v > sig[i - 1][1] and v >= sig[i + 1][1] and v > 0.15:
        near = any(abs(st - t) < 0.15 for st in steps)
        print(f"{t:6.2f}  {v:9.2f}  {'KORAK' if near else ('  (ispod praga)' if v < THRESHOLD else '  ?')}")
