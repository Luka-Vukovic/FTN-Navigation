"""Smer hoda sa znakom iz faze: w = sum_lag sign(lag) * sum_t h(t) v(t+lag), h = horizontalno (e, n), v = vertikalno."""
import math, sys
sys.path.insert(0, 'tools/pdr')
from explore_sign import load

def phase_dir(w, lags=range(1, 16)):
    N=len(w)
    me=sum(s[1] for s in w)/N; mn=sum(s[2] for s in w)/N; mu=sum(s[3] for s in w)/N
    e=[s[1]-me for s in w]; n=[s[2]-mn for s in w]; v=[s[3]-mu for s in w]
    ce=cn=0.0
    for lag in lags:
        for i in range(N-lag):
            # h(t) * v(t+lag) - h(t+lag) * v(t)  (= +lag i -lag)
            ce+=e[i]*v[i+lag]-e[i+lag]*v[i]
            cn+=n[i]*v[i+lag]-n[i+lag]*v[i]
    sh=math.sqrt(sum(a*a+b*b for a,b in zip(e,n))/N); sv=math.sqrt(sum(x*x for x in v)/N)
    strength=math.hypot(ce,cn)/(N*len(lags))/(sh*sv) if sh*sv>0 else 0
    return math.degrees(math.atan2(ce,cn))%360, strength

def phone(w):
    pe=sum(math.sin(math.radians(s[4])) for s in w); pn=sum(math.cos(math.radians(s[4])) for s in w)
    return math.degrees(math.atan2(pe,pn))%360

if __name__ == '__main__':
    path=sys.argv[1]; win=float(sys.argv[2]) if len(sys.argv)>2 else 2.0
    s,steps=load(path); t0=s[0][0]
    print("     t  telefon  faza  jačina  razlika")
    i=0
    while i<len(s):
        j=i
        while j<len(s) and s[j][0]-s[i][0]<win*1e9: j+=1
        w=s[i:j]
        if len(w)>50:
            d,st=phase_dir(w); p=phone(w)
            diff=(d-p+540)%360-180
            print(f"{(s[i][0]-t0)/1e9:6.1f} {p:7.0f} {d:6.0f} {st:6.2f} {diff:+7.0f}")
        i=j
