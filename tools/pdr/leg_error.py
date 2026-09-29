"""Greška smera po delovima putanje sa poznatim pravcem (snimci džepa sa beleškama, 29.09.2026).

  python tools/pdr/leg_error.py [app/build/pdr-replay]

Čita tabele iz WalkingDirectionReplayTest (kolona "ponovljeno") i za svaki deo računa srednju
grešku (kružna sredina smera - očekivano) i srednju apsolutnu grešku po koraku. Pretpostavka
(korisnik): pravi uglovi, prvi pravac kao u ruci ~95°; putanja 95, 5, 275 | 180° | 95, 185, 275.
"""
import math
import os
import sys

PATH = [95, 5, 275, 95, 185, 275]

# Snimak -> delovi: (naziv, [(od, do) za svaki od 6 pravaca]).
LEGS = {
    'hod-20260929-174925': [
        ('naopako', [(13.2, 17.1), (19.8, 21.8), (24.8, 31.2), (32.5, 34.0), (36.6, 38.4), (40.5, 47.6)]),
        ('uspravno', [(57.6, 65.5), (68.0, 70.3), (71.9, 75.3), (78.6, 80.0), (83.4, 85.1), (87.4, 92.0)]),
    ],
    'hod-20260929-181246': [
        ('naopako', [(9.4, 17.2), (19.5, 24.0), (25.4, 38.3), (40.9, 51.0), (55.0, 59.5), (61.7, 71.3)]),
    ],
    'hod-20260929-182544': [
        ('naopako', [(85.0, 95.0), (96.5, 101.5), (102.0, 113.0), (115.0, 125.0), (126.0, 130.5), (132.0, 142.0)]),
        ('uspravno', [(159.6, 167.0), (167.7, 173.0), (174.5, 186.0), (187.4, 196.5), (199.5, 203.5), (205.0, 214.0)]),
    ],
}


def adiff(a, b):
    return (a - b + 540) % 360 - 180


def main(folder):
    total = []
    for name, parts in LEGS.items():
        f = os.path.join(folder, name + '.txt')
        if not os.path.exists(f):
            continue
        steps = []
        for line in open(f, encoding='utf-8'):
            p = line.split()
            if len(p) > 4 and p[1].isdigit() and p[0][0].isdigit():
                steps.append((float(p[0]), float(p[3].rstrip('°'))))
        for part, legs in parts:
            errs = []
            means = []
            for (a, b), want in zip(legs, PATH):
                e = [adiff(h, want) for t, h in steps if a <= t <= b]
                if not e:
                    continue
                errs += e
                means.append(sum(e) / len(e))
            mae = sum(abs(x) for x in errs) / len(errs)
            total += errs
            print(f"{name} {part:9s}: po delu {' '.join(f'{m:+4.0f}' for m in means)}  | sr. aps. {mae:4.1f}°")
    print(f"UKUPNO sr. aps. greška {sum(abs(x) for x in total) / len(total):4.1f}° ({len(total)} koraka)")


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'app/build/pdr-replay')
