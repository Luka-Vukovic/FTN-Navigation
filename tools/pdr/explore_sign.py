"""Istraživanje: osa hoda preko prozora i znak napred/nazad iz faze vertikalnog i uzdužnog ubrzanja."""
import math, sys

def load(path):
    R=None; out=[]; steps=[]
    for l in open(path):
        f=l.rstrip().split(',')
        if f[0]=='R': R=[float(x) for x in f[2:11]]
        elif f[0]=='A' and R:
            t=int(f[1]); x,y,z=(float(v) for v in f[2:5])
            e=R[0]*x+R[1]*y+R[2]*z; n=R[3]*x+R[4]*y+R[5]*z; u=R[6]*x+R[7]*y+R[8]*z
            ph=math.degrees(math.atan2(R[1]-R[2],R[4]-R[5]))%360
            out.append((t,e,n,u,ph))
        elif f[0]=='S': steps.append(int(f[1]))
    return out,steps

def analyze(samples):
    N=len(samples)
    me=sum(s[1] for s in samples)/N; mn=sum(s[2] for s in samples)/N; mu=sum(s[3] for s in samples)/N
    see=sum((s[1]-me)**2 for s in samples)/N; snn=sum((s[2]-mn)**2 for s in samples)/N; sen=sum((s[1]-me)*(s[2]-mn) for s in samples)/N
    half=(see+snn)/2; spread=math.sqrt(((see-snn)/2)**2+sen**2)
    ang=0.5*math.atan2(2*sen,see-snn)
    axis=math.degrees(math.atan2(math.cos(ang),math.sin(ang)))%360  # azimut
    ratio=(half-spread)/(half+spread)
    # prosečan pravac telefona
    pe=sum(math.sin(math.radians(s[4])) for s in samples); pn=sum(math.cos(math.radians(s[4])) for s in samples)
    phone=math.degrees(math.atan2(pe,pn))%360
    a=math.radians(axis)
    f=[(s[1]-me)*math.sin(a)+(s[2]-mn)*math.cos(a) for s in samples]
    v=[s[3]-mu for s in samples]
    sf=math.sqrt(sum(x*x for x in f)/N); sv=math.sqrt(sum(x*x for x in v)/N)
    cc=[]
    for lag in range(-15,16,3):
        c=0;k=0
        for i in range(N):
            j=i+lag
            if 0<=j<N: c+=f[i]*v[j]; k+=1
        cc.append(c/k/(sf*sv))
    return axis,ratio,phone,cc

if __name__ == '__main__':
    path=sys.argv[1]; win=float(sys.argv[2]) if len(sys.argv)>2 else 2.5
    s,steps=load(path); t0=s[0][0]
    i=0
    print("  t0    osa  odn  tel  | korelacija f(t)*v(t+lag), lag -0.30..+0.30 s po 0.06")
    while i<len(s):
        j=i
        while j<len(s) and s[j][0]-s[i][0]<win*1e9: j+=1
        w=s[i:j]
        if len(w)>50:
            axis,ratio,phone,cc=analyze(w)
            print(f"{(s[i][0]-t0)/1e9:6.1f} {axis:5.0f} {ratio:4.2f} {phone:4.0f}  | "+" ".join(f"{c:+.2f}" for c in cc))
        i=j
