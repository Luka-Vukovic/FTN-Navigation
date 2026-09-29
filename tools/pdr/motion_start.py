"""Početak pokreta telefona pre prepoznatog premeštanja, za više pragova - izbor MOTION_START_DEG.

  python tools/pdr/motion_start.py pdr/hod-....csv [...]

Prepoznavanje kao WalkingDirection (brzo izglađeno "gore" okrenuto > 45° za 1,5 s). Za svako
premeštanje: prvi trenutak (4 ili 5 s unazad) kad se "gore" odmaklo od najstarijeg za više od
praga (10/15/20°) i smer koraka pre toga (zabeležen smer iz snimka).
"""
import math, sys
A=0.05
def ang(a,b):
    d=sum(x*y for x,y in zip(a,b)); n=math.sqrt(sum(x*x for x in a)*sum(x*x for x in b))
    return math.degrees(math.acos(max(-1,min(1,d/n))))
for path in sys.argv[1:]:
    up=None; hist=[]; t0=None; steps=[]; events=[]; anchored_until=-1
    for l in open(path):
        f=l.split(',')
        if f[0]=='S':
            steps.append(((int(f[1])-t0)/1e9, float(f[2])))
            continue
        if f[0]!='R': continue
        t=int(f[1]); t0=t0 or t; s=(t-t0)/1e9
        r=[float(x) for x in f[8:11]]
        up=list(r) if up is None else [u+A*(x-u) for u,x in zip(up,r)]
        hist.append((s,up))
        if s<anchored_until: continue
        w=[h for h in hist if s-h[0]<=1.5]
        if ang(up,w[0][1])>45:
            events.append((s,[h for h in hist if s-h[0]<=6.0]))
            anchored_until=s+3.0
    print("==", path)
    def heading_at(t):
        prev=[h for ts,h in steps if ts<=t]
        return prev[-1] if prev else None
    for s,h in events:
        line=f"  prepoznato {s:5.1f} s (smer tada {heading_at(s)}; stari 0,5-1 s pre: {heading_at(s-0.75)})"
        print(line)
        for L in (4.0,5.0):
            for th in (10,15,20):
                hh=[x for x in h if s-x[0]<=L]
                ref=hh[0][1]
                dep=next((x[0] for x in hh if ang(x[1],ref)>th), s)
                print(f"     unazad {L:.0f} s, prag {th:2d}°: početak {dep:5.1f} s ({s-dep:3.1f} s ranije), smer pre početka {heading_at(dep-0.25)}")
