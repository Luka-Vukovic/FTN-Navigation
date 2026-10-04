"""
Plan FTN dela Naučno-tehnološkog parka (NTP): šematski crteži spratova i unutrašnji graf.

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/ntp/build_ntp.py [--check <folder sa w0..w5.png>]

Pravi:
  - app/src/main/res/drawable/floor_plan_ntp_{0..5}.xml (prizemlje ... V sprat),
  - app/src/main/assets/ntp.json (čvorovi, ivice, veze sa grafom kampusa),
  - uz --check: <folder>/check{0..5}.png - crtež i graf preko fotografija evakuacionih planova.

Izvor: fotografije evakuacionih planova (images/ntp0..5.jpg, van gita). Perspektiva je
ispravljena po spoljnom okviru plana (odnos stranica √2 - prsten "zborno mesto" mora biti okrugao),
pa je svaki sprat poravnat sa II spratom po stepeništima i liftovima (sličnost: razmera + pomeraj;
liftovi se na svim spratovima poklapaju na par px). Sve koordinate ispod su u tom zajedničkom
sistemu (px ispravljenog II sprata). Viewport plana obuhvata ceo OSM obris NTP-a (FTN deo + poslovni
deo, crta se svetlo na svim spratovima), jer je NTP-A u poslovnom delu.

Spratovi II-IV su isti crtež (ljuska, jezgra, hodnici i kancelarije se poklapaju; razlikuju se
samo pregrade soba u sredini) - "tipičan sprat" (TYPICAL), uz pregrade podeljenih i spojene sobe po spratu
(typical_plan). I sprat ima svoj crtež (FIRST, teren 04.10.2026 - nije isti kao II i III). Planovi nemaju brojeve sala; do
03.10.2026 su sale iz rasporeda bile na izmišljenim mestima, a tada ih je korisnik očitao sa vrata
na svim spratovima (TYPICAL_LABELS, TOP_LABELS, prizemlje). Na planu prizemlja piše L1, L2, L4, LAB,
AMFITEATAR: NTP-L3 = LAB je pretpostavka, "AMFITEATAR" je u stvari učionica 001, a na vratima "L2" piše C.
NTP-A NIJE taj amfiteatar: u prizemlju je, ali u poslovnom delu NTP-a (teren 01.10.2026, korisnik
nacrtao na mapi kampusa); do njega se ide kroz predvorje "ULAZ - FTN" (PRETPOSTAVKA). Teren 02.10.2026:
korisnik ga je nacrtao ponovo, na planu - poravnat je sa planom (paralelno/upravno sa ostalim zidovima) i
nije uz zapadni zid zgrade (NTP_A); prvi crtež (na mapi kampusa) je bio zarotiran i uz zid.
Evakuacioni putevi se ne koriste (korisnik, 02.10.2026): terasa V sprata do desnog jezgra je samo crtež.

Smeštaj u kampus (M_PER_PX, PLAN_TIP, plan_transform; koristi ga i build_campus.py): donji desni vrh
plana -> severni teme OSM obrisa, dole na planu (red kancelarija, parking) uz severoistočni zid, dijagonala
je Fruškogorska (zapadni zid). Razmera 0,029 m/px (teren 01.10.2026): tada GLAVNI ULAZ - FTN pada 1,4 m od
ulaza koji je korisnik označio sa strane domova, oba kraja PASAŽA na zidove OSM obrisa (do 1 m), a
"ULAZ - FTN" ~5 m od ulaza kod pešačkog prelaza na Fruškogorskoj. Hodnik III sprata (1321 px) je tada
38 m = 64,5 pločice od ~0,59 m / 47 koraka. Ranije 0,042 (pretpostavka) - ~1,45x preveliko.
"""

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VX, VY, VW, VH = -1650, -720, 3790, 2580  # viewport plana (zajednički sistem, px): ceo OSM obris NTP-a
BUILDING = "NTP"
FLOORS = range(6)

# --- Smeštaj u kampus -------------------------------------------------------------------------

M_PER_PX = 0.029
PLAN_TIP = (2092.0, 1830.0)  # donji desni vrh plana = severni teme OSM obrisa


def plan_transform(ring):
    """(px plana -> metri kampusa, metri kampusa -> px plana) za OSM obris NTP-a [ring]: vrh plana ->
    najseverniji teme, levo na planu -> ka jugoistočnom uglu."""
    tip = min(ring, key=lambda p: p[1])
    southeast = max(ring, key=lambda p: p[0])
    d = complex(*southeast) - complex(*tip)
    z = -d / abs(d) * M_PER_PX  # (-1, 0) na planu -> ka jugoistoku
    t0, c0 = complex(*PLAN_TIP), complex(*tip)

    def to_campus(p):
        q = c0 + z * (complex(*p) - t0)
        return q.real, q.imag

    def to_plan(q):
        p = t0 + (complex(*q) - c0) / z
        return round(p.real), round(p.imag)

    return to_campus, to_plan, z


def osm_ring():
    """OSM obris NTP-a (metri kampusa) iz assets/campus.json (build_campus.py ga uzima sa Overpass-a)."""
    campus = json.loads((ROOT / "app/src/main/assets/campus.json").read_text(encoding="utf-8"))
    return [tuple(p) for b in campus["buildings"] if b["id"] == BUILDING for p in b["outline"]]


_TO_CAMPUS, TO_PLAN, _ = plan_transform(osm_ring())
OSM_OUTLINE = [TO_PLAN(q) for q in osm_ring()]  # ceo NTP (i poslovni deo), px plana

# Teren 01.10.2026, korisnik nacrtao na mapi kampusa (metri kampusa): ulaz kod pešačkog prelaza na Fruškogorskoj
# (= "ULAZ - FTN" sa evakuacionog plana).
ULAZ_FTN = TO_PLAN((35.2, 292.6))
# NTP-A (amfiteatar u poslovnom delu): teren 02.10.2026, korisnik nacrtao na snimku ekrana plana prizemlja; px plana
# preko čvorova ULAZ-FTN, V1, V0 na snimku (centar starog NTP-A pogođen na 2 px). 12,5 x 17,5 m, ~1-6 m od zida.
NTP_A = [(-627, -325), (-627, 277), (-1057, 277), (-1057, -325)]  # vrata na prvoj stranici (istok, ka predvorju)

# --- Geometrija (px) -------------------------------------------------------------------------

FACADE = [(710, 332), (1100, 610), (1747, 1090), (2075, 1378), (2092, 1830)]  # dijagonala + vrh

TYPICAL = {
    "outline": [(350, 332), *FACADE, (336, 1830), (336, 1556), (264, 1556), (264, 1397), (345, 1397),
                (345, 1297), (126, 1297), (126, 1020), (60, 1020), (60, 640), (126, 640), (126, 547), (350, 547)],
    "corridors": [
        [(350, 332), (451, 332), (451, 1660), (350, 1660)],   # glavni hodnik (levo)
        [(451, 547), (1000, 547), (1000, 640), (451, 640)],   # gornji hodnik
        [(451, 1280), (1822, 1280), (1822, 1389), (451, 1389)],  # srednji hodnik
        [(1301, 800), (1402, 800), (1402, 1280), (1301, 1280)],  # desni hodnik
        [(1305, 1389), (1397, 1389), (1397, 1556), (1305, 1556)],
        [(1765, 1389), (1822, 1389), (1822, 1556), (1765, 1556)],
        [(451, 1556), (2081, 1556), (2081, 1660), (451, 1660)],  # donji hodnik
    ],
    "rooms": [
        [(126, 547), (345, 547), (345, 640), (126, 640)],
        [(60, 640), (345, 640), (345, 833), (60, 833)],
        [(60, 833), (345, 833), (345, 1020), (60, 1020)],
        [(126, 1020), (345, 1020), (345, 1204), (126, 1204)],
        [(126, 1204), (345, 1204), (345, 1297), (126, 1297)],
        [(445, 646), (666, 646), (666, 817), (445, 817)],  # predvorje liftova
        *[[(a, 646), (b, 646), (b, 817), (a, 817)] for a, b in ((666, 765), (765, 869), (869, 974), (974, 1073))],
        [(451, 817), (666, 817), (666, 1000), (451, 1000)],
        [(451, 1000), (666, 1000), (666, 1204), (451, 1204)],
        [(451, 1204), (666, 1204), (666, 1280), (451, 1280)],
        [(666, 817), (1073, 817), (1073, 1280), (666, 1280)],
        [(1073, 817), (1301, 817), (1301, 1040), (1073, 1040)],
        [(1073, 1040), (1301, 1040), (1301, 1280), (1073, 1280)],
        [(1402, 834), (1747, 1090), (1822, 1146), (1822, 1280), (1402, 1280)],  # otvoren prostor
        [(1822, 1146), (2075, 1378), (2081, 1556), (1822, 1556)],
        [(699, 1389), (930, 1389), (930, 1556), (699, 1556)],
        [(930, 1389), (1035, 1389), (1035, 1556), (930, 1556)],
        [(1035, 1389), (1305, 1389), (1305, 1556), (1035, 1556)],  # toaleti
        *[[(a, 1660), (b, 1660), (b, 1830), (a, 1830)] for a, b in zip(
            [336, 451, 572, 693, 814, 930, 1048, 1167, 1285, 1402, 1523, 1638, 1759, 1880],
            [451, 572, 693, 814, 930, 1048, 1167, 1285, 1402, 1523, 1638, 1759, 1880, 2090])],
        # Teren 04.10.2026 (dodato na kraj - indeksi soba iznad ostaju isti, na njih se oslanjaju TYPICAL_LABELS):
        [(450, 715), (550, 715), (550, 815), (450, 815)],  # 35: toalet uz liftove ("svaki sprat osim prizemlja")
        [(1073, 591), (1100, 610), (1301, 759), (1301, 817), (1073, 817)],  # 36: iznad 14, do fasade (318, 219)
    ],
    "stairs": [(445, 343, 616, 547), (451, 1397, 699, 1550), (1575, 1395, 1765, 1545)],
    "shafts": [(658, 338, 710, 547)],
    "lifts": [(460, 651, 512, 701), (515, 651, 567, 701), (270, 1470, 335, 1545), (1460, 1487, 1523, 1545)],
}

# I sprat (teren 04.10.2026, nova fotografija - korisnik: "nije skroz isto kao drugi i treći sprat"). Gornji hodnik se
# posle šahta sužava (y 591-680) i završava dvokrilnim vratima (x 933) ka predvorju pod fasadom; iznad njega je soba do
# fasade; sobe 7-9 su niže (680-827), a prostor 6 je otvoren ka hodniku (razvodni orman). Trougao desno od desnog
# hodnika (na II-IV jedna soba, 16) ima pregrade na x 1575 i 1785: T1 i T2 (dvokrilna vrata dole, na pregradi) i T3
# (desno, ulaz sa kraja srednjeg hodnika, koji se završava vratima na x 1785). Soba 17 je samo donji deo
# (1825-2083 x 1400-1590), ulazi se iz T3 odozgo. Donji hodnik se iza dvokrilnih vrata (x 1765) nastavlja ispod sobe 17.
# Polovine 14 i 15 (115-118) su ovde nacrtane.
FIRST = {
    "outline": TYPICAL["outline"],
    "corridors": [
        [(350, 332), (451, 332), (451, 1660), (350, 1660)],  # glavni hodnik (levo)
        [(451, 547), (708, 547), (708, 591), (933, 591), (933, 680), (767, 680), (767, 827), (664, 827), (664, 640),
         (451, 640)],  # gornji hodnik, sužen posle šahta, sa otvorenim prostorom 6
        [(933, 591), (1073, 591), (1073, 680), (933, 680)],  # predvorje iza dvokrilnih vrata
        [(451, 1280), (1785, 1280), (1785, 1389), (451, 1389)],  # srednji hodnik
        [(1301, 800), (1402, 800), (1402, 1280), (1301, 1280)],  # desni hodnik
        [(1305, 1389), (1397, 1389), (1397, 1556), (1305, 1556)],
        [(1785, 1280), (1825, 1280), (1825, 1590), (1765, 1590), (1765, 1389), (1785, 1389)],  # prolaz ka donjem
        [(451, 1556), (1765, 1556), (1765, 1590), (2083, 1590), (2081, 1660), (451, 1660)],  # donji hodnik
    ],
    "rooms": [
        *TYPICAL["rooms"][0:6],  # levo krilo, kutija sa liftovima
        [(710, 335), (933, 491), (933, 591), (710, 591)],  # iznad gornjeg hodnika, do fasade
        *[[(a, 680), (b, 680), (b, 827), (a, 827)] for a, b in ((767, 867), (867, 967), (967, 1073))],
        *TYPICAL["rooms"][10:14],  # srednji red, velika sala
        *[[(1073, a), (1301, a), (1301, b), (1073, b)] for a, b in ((817, 929), (929, 1040), (1040, 1160), (1160, 1280))],
        [(1402, 834), (1575, 962), (1575, 1280), (1402, 1280)],  # T1
        [(1575, 962), (1747, 1090), (1785, 1123), (1785, 1280), (1575, 1280)],  # T2
        [(1785, 1123), (2075, 1378), (2076, 1400), (1825, 1400), (1825, 1280), (1785, 1280)],  # T3
        [(1825, 1400), (2076, 1400), (2083, 1590), (1825, 1590)],  # 17 (124)
        *TYPICAL["rooms"][18:],  # donji srednji red, toaleti, kancelarije, toalet uz liftove, soba iznad 14
    ],
    "stairs": TYPICAL["stairs"],
    "shafts": TYPICAL["shafts"],
    "lifts": TYPICAL["lifts"],
}

TOP = {  # V sprat: manji, ispod srednjeg hodnika je krov sa dve evakuacione staze
    "roof": TYPICAL["outline"],
    "outline": [(350, 332), (710, 332), (1100, 610), (1305, 745), (1305, 1556), (264, 1556), (264, 1397),
                (345, 1397), (345, 1297), (121, 1297), (121, 547), (350, 547)],
    "outline2": [(1397, 1383), (1782, 1383), (1782, 1562), (1397, 1562)],  # desno jezgro
    "terraces": [
        [(1305, 1297), (1782, 1297), (1782, 1383), (1305, 1383)],
        [(1305, 1383), (1397, 1383), (1397, 1830), (1305, 1830)],
        [(336, 1556), (451, 1556), (451, 1830), (336, 1830)],
    ],
    "corridors": [
        [(350, 332), (451, 332), (451, 1556), (350, 1556)],
        [(451, 547), (1000, 547), (1000, 640), (451, 640)],
        [(451, 1280), (1305, 1280), (1305, 1389), (451, 1389)],
        [(1080, 813), (1150, 813), (1150, 1280), (1080, 1280)],
    ],
    "rooms": [
        [(121, 547), (345, 547), (345, 762), (121, 762)],
        [(121, 762), (345, 762), (345, 1020), (121, 1020)],
        [(121, 1020), (345, 1020), (345, 1204), (121, 1204)],
        [(121, 1204), (345, 1204), (345, 1297), (121, 1297)],
        [(445, 646), (666, 646), (666, 817), (445, 817)],
        [(765, 646), (869, 646), (869, 817), (765, 817)],
        [(451, 817), (666, 817), (666, 1040), (451, 1040)],
        [(451, 1040), (666, 1040), (666, 1280), (451, 1280)],
        [(666, 817), (1080, 817), (1080, 1280), (666, 1280)],
        *[[(1150, a), (1305, a), (1305, b), (1150, b)] for a, b in
          ((813, 900), (900, 990), (990, 1080), (1080, 1180), (1180, 1280))],
        [(699, 1389), (930, 1389), (930, 1556), (699, 1556)],
        [(930, 1389), (1120, 1389), (1120, 1556), (930, 1556)],
        [(1120, 1389), (1305, 1389), (1305, 1556), (1120, 1556)],
    ],
    "stairs": [(445, 343, 616, 547), (451, 1397, 699, 1550), (1586, 1395, 1776, 1545)],
    "shafts": [(658, 338, 710, 547)],
    "lifts": [(460, 651, 512, 701), (515, 651, 567, 701), (270, 1470, 335, 1545), (1460, 1487, 1523, 1545)],
}

GROUND = {
    "outline": [(125, 0), (160, 0), *[(704, 338)] + FACADE[1:], (300, 1830), (300, 1557), (-156, 1557),
                (-156, 1276), (125, 1276)],
    "corridors": [
        [(125, 0), (160, 0), (447, 178), (450, 1830), (350, 1830), (350, 1300), (125, 1300)],  # hol
        [(-275, 50), (125, 50), (125, 190), (-275, 190)],  # predvorje "ULAZ - FTN" (sa Fruškogorske)
        [(ULAZ_FTN[0] - 60, ULAZ_FTN[1] - 30), (ULAZ_FTN[0] + 60, ULAZ_FTN[1] - 30), (ULAZ_FTN[0] + 60, 50),
         (ULAZ_FTN[0] - 60, 50)],  # do spoljnog zida (PRETPOSTAVKA - na planu se izlazi gore iz predvorja)
        [(-156, 1300), (125, 1300), (125, 1395), (-156, 1395)],  # levo krilo
        [(447, 551), (707, 551), (707, 646), (447, 646)],
        [(654, 646), (719, 646), (719, 905), (654, 905)],
        [(719, 829), (1096, 829), (1096, 905), (719, 905)],
        [(1143, 642), (1403, 835), (1403, 1830), (1299, 1830), (1299, 1272), (1096, 1272), (1096, 829),
         (1143, 829)],  # PASAŽ
        [(1403, 1297), (1780, 1297), (1780, 1394), (1403, 1394)],
        [(450, 1740), (812, 1740), (812, 1830), (450, 1830)],
    ],
    "rooms": [
        [(719, 348), (1143, 642), (1143, 829), (719, 829)],  # LAB
        [(450, 905), (654, 905), (654, 1283), (450, 1283)],  # amfiteatar
        [(654, 905), (1096, 905), (1096, 1283), (654, 1283)],  # atrijum
        [(1043, 1272), (1299, 1272), (1299, 1547), (1043, 1547)],  # L2
        [(1043, 1553), (1299, 1553), (1299, 1830), (1043, 1830)],  # L1
        [(1403, 835), (1747, 1090), (1756, 1097), (1756, 1297), (1403, 1297)],  # L4
        [(1780, 1115), (2075, 1378), (2080, 1547), (1780, 1547)],
        [(1403, 1559), (1573, 1559), (1573, 1830), (1403, 1830)],  # dizel agregat
        [(1573, 1559), (1628, 1559), (1628, 1830), (1573, 1830)],
        [(1634, 1559), (2085, 1559), (2085, 1687), (1634, 1687)],  # razvodni blok
        [(1634, 1687), (2085, 1687), (2085, 1830), (1634, 1830)],  # trafo stanica
        [(450, 1295), (700, 1295), (700, 1395), (450, 1395)],  # spremačice
        *[[(a, 1551), (b, 1551), (b, 1740), (a, 1740)] for a, b in ((450, 570), (570, 690), (690, 812))],
        [(81, 1407), (206, 1407), (206, 1551), (81, 1551)],  # kancelarija
        [(-50, 1407), (81, 1407), (81, 1551), (-50, 1551)],  # WC
        [(-156, 1407), (-50, 1407), (-50, 1551), (-156, 1551)],
        [(447, 646), (660, 646), (660, 817), (447, 817)],  # jezgro sa liftovima
    ],
    "ramps": [(812, 1283, 1043, 1830)],
    "stairs": [(445, 343, 616, 547), (512, 1395, 700, 1551), (1586, 1394, 1769, 1541), (447, 705, 640, 810)],
    "shafts": [(658, 338, 707, 551)],
    "lifts": [(460, 651, 512, 701), (515, 651, 567, 701), (270, 1470, 335, 1545), (1463, 1491, 1524, 1547)],
    "entrances": [(ULAZ_FTN[0] - 60, ULAZ_FTN[1] - 30, ULAZ_FTN[0] + 60, ULAZ_FTN[1] - 10),
                  (1299, 1830, 1403, 1846), (300, 1830, 450, 1846)],
    "business_rooms": [NTP_A],
}

# --- Graf -------------------------------------------------------------------------------------

# Oznake sala: teren 03.10.2026 (korisnik, stranica "NTP prostorije" - dodir sobe na planu -> oznaka sa vrata).
# Soba se zadaje indeksom u TYPICAL["rooms"] / TOP["rooms"] / GROUND["rooms"] (isti indeksi kao na stranici - ako
# se lista soba promeni, oznake treba preneti). Vrata su na sredini zida ka hodniku (kao u NB-u), osim gde je
# drugačije napisano. Za svaku sobu: (centar, vrata, čvor hodnika).
OFFICES = [393, 511, 632, 753, 872, 989, 1107, 1226, 1343, 1462, 1580, 1698, 1819, 1985]
TYPICAL_DOORS = {
    0: ((235, 593), (345, 593), "M595"), 1: ((200, 736), (345, 736), "M736"), 2: ((200, 927), (345, 927), "M927"),
    3: ((235, 1112), (345, 1112), "M1112"), 4: ((235, 1250), (345, 1250), "M1250"),
    6: ((715, 730), (715, 646), "U715"), 7: ((817, 730), (817, 646), "U817"),
    8: ((921, 730), (921, 646), "U921"), 9: ((1023, 730), (1023, 646), "U1023"),
    10: ((559, 908), (451, 908), "M927"), 11: ((559, 1102), (451, 1102), "M1112"), 12: ((559, 1242), (451, 1242), "M1250"),
    14: ((1187, 929), (1301, 929), "R930"), 15: ((1187, 1160), (1301, 1160), "R1160"),
    16: ((1612, 1057), (1612, 1280), "C1600"), 17: ((1952, 1351), (1822, 1470), "K1795"),
    18: ((815, 1473), (815, 1389), "C815"), 19: ((983, 1473), (983, 1389), "C983"), 20: ((1170, 1473), (1170, 1389), "C1170"),
    **{21 + i: ((x, 1745), (x, 1660), "M1608" if x == 393 else f"B{x}") for i, x in enumerate(OFFICES)},
}
# Sobe podeljene na dve (korisnik: "razdvojeno", "razdvojeno staklom"), prva je gornja/leva. Teren 04.10.2026 (korisnik)
# potvrđuje redosled: "115 je najbliže zidu zgrade, pa redom do hodnika 115, 116, 117, 118"; "320 je bliže 317-319, onda
# ide 321"; "322 je bliže 321, a 323 je bliže 324. U 323 se ulazi preko 322"; "223 i 224 analogno 322 i 323" (VIA_FIRST).
SPLIT_DOORS = {
    14: [((1187, 873), (1301, 873), "R930"), ((1187, 985), (1301, 985), "R930")],
    15: [((1187, 1100), (1301, 1100), "R1160"), ((1187, 1220), (1301, 1220), "R1160")],
    16: [((1500, 1057), (1500, 1280), "C1494"), ((1720, 1057), (1720, 1280), "C1795")],
    17: [((1952, 1260), (1822, 1334), "C1795"), ((1952, 1460), (1822, 1470), "K1795")],
}
# Sobe koje nisu na crtežu (korisnik je dodirnuo mesto van nacrtanih soba): (centar, vrata, čvor) - PRETPOSTAVKA.
ABOVE_RIGHT = ((1170, 765), (1301, 805), "R830")  # iznad sobe 14, levo od početka desnog hodnika
# Sprat -> [(soba, broj ili brojevi)]; više brojeva u jednoj sobi: prvi je naziv, ostali su alias (ROOM_ALIASES u
# Destinations.kt), osim podeljenih soba (SPLIT_DOORS). 111 (I sprat, "između 112 i lifta") nije ucrtan.
TYPICAL_LABELS = {
    1: [(0, 106), (1, 107), (2, 108), (3, 109), (4, 110), (10, 112), (11, 113), (12, 114), (14, (115, 116)),
        (15, (117, 118)), (16, (119, 120, 121, 122, 123)), (17, 124), (18, 139), (19, 140),
        *[(21 + i, 138 - i) for i in range(14)]],
    2: [(1, 208), (2, 209), (3, 210), (4, 211), (6, 202), (10, 212), (11, 213), (12, 214), (14, 218), (15, 217),
        (16, (221, 222)), (17, (223, 224)), (18, 215), (19, 216), ("above", 219), *[(21 + i, 238 - i) for i in range(14)]],
    3: [(1, 307), (2, 308), (3, 309), (4, 310), (10, 311), (11, 312), (14, 317), (15, 316), ("above", 318),
        (16, (320, 321)), (17, (322, 323)), (18, 313), (19, 314), (20, 315), *[(21 + i, 337 - i) for i in range(14)]],
    4: [(1, 407), (2, 408), (3, 409), (6, 402), (7, 403), (8, 404), (9, 405), (10, 410), (11, 411), (12, 412),
        (14, 416), (15, 415), (16, 417), (17, 418), (18, 413), (19, 414), ("corner", 419),
        *[(21 + i, 433 - i) for i in range(14)]],
}
SPLIT = {1: {14, 15}, 2: {17}, 3: {16, 17}}
# Pregrade podeljenih soba na crtežu II-IV (I sprat ima svoje, FIRST): sredina između vrata polovina, do fasade.
SPLIT_SHAPES = {
    16: [[(1402, 834), (1612, 990), (1612, 1280), (1402, 1280)],
         [(1612, 990), (1747, 1090), (1822, 1146), (1822, 1280), (1612, 1280)]],
    17: [[(1822, 1146), (2055, 1360), (1822, 1360)],
         [(1822, 1360), (2055, 1360), (2075, 1378), (2081, 1556), (1822, 1556)]],
}
# Soba 17 na II i III spratu: u drugu polovinu (224, 323) se ulazi kroz prvu (223, 322) - prva je prolaz (čvor hodnika
# P17 u njoj; sala nije usputni čvor grafa), druga ima vrata u pregradi. Gornja polovina je uzan trougao pod fasadom.
VIA_FIRST = {2, 3}
VIA_17 = {"pass": (1865, 1334), "first": (1925, 1315), "door": (1940, 1360), "second": (1952, 1460)}
# I sprat: 119-123 (jedna sala, aliasi) je za sada T1 (PRETPOSTAVKA - korisnik će označiti 119-123 na novom planu); 124
# (soba 17) se ulazi iz T3, a T3 sa kraja srednjeg hodnika (čvor T3 u njoj, kao P17).
FIRST_DOORS = {16: ((1490, 1120), (1550, 1280), "C1494")}
FIRST_T3 = {"door": (1825, 1345), "pass": (1880, 1345), "room17": ((1950, 1495), (1885, 1400))}
# 419 (IV sprat, kancelarija): korisnik je dodirnuo kraj donjeg hodnika desno (1971, 1597) - PRETPOSTAVKA: soba na
# kraju hodnika, vrata ka hodniku.
CORNER_419 = ((1990, 1600), (1925, 1608), "B1985")

# V sprat (TOP["rooms"]): korisnik - vrata "na kraju" kod 504-510 (sada ipak na sredini, kao drugde).
TOP_LABELS = {
    0: (504, (233, 655), (345, 655), "M676"), 1: (505, (233, 891), (345, 891), "M890"),
    2: (506, (233, 1112), (345, 1112), "M1112"), 6: (507, (559, 929), (451, 929), "M890"),
    7: (508, (559, 1160), (451, 1160), "M1112"),
    **{i: (n, (1228, y), (1150, y), f"R{y}") for i, n, y in zip(range(9, 14), range(516, 511, -1), (856, 945, 1035, 1130, 1230))},
    14: (509, (815, 1473), (815, 1389), "C815"), 15: (510, (1025, 1473), (1025, 1389), "C1025"),
    16: (511, (1213, 1473), (1213, 1389), "C1212"),
}

# Isto mesto na svakom spratu (liftovi se na planovima poklapaju); stepenice su susedni spratovi.
LIFTS = {"L1": (513, 676), "L2": (302, 1505), "L3": (1492, 1516)}
STAIRS = ["S1", "S2", "S3"]

# Ulazi (ULAZ) prizemlja -> ulaz u grafu kampusa (build_campus.py pravi K-U-NTP-n na istim tačkama).
ENTRANCES = {
    "ULAZ-PASAZ-I": ((1350, 1830), "K-U-NTP-1"),  # PASAŽ, istočni kraj (parking)
    "ULAZ-PASAZ-Z": ((1215, 700), "K-U-NTP-2"),  # PASAŽ, zapadni kraj (Fruškogorska)
    "ULAZ": ((375, 1830), "K-U-NTP-3"),  # "GLAVNI ULAZ - FTN"
    "ULAZ-FTN": (ULAZ_FTN, "K-U-NTP-4"),  # "ULAZ - FTN" (gore levo na planu), kod pešačkog prelaza
}
MAIN_ENTRANCE = "ULAZ"


class Graph:
    def __init__(self):
        self.nodes = {}  # id -> (sprat, x, y, tip, naziv)
        self.edges = {}  # (a, b) -> tip

    def node(self, floor, key, x, y, kind="HODNIK", name=None):
        nid = f"{BUILDING}-{floor}-{key}"
        self.nodes.setdefault(nid, (floor, x, y, kind, name))
        return nid

    def edge(self, a, b, kind="HOD"):
        assert a in self.nodes and b in self.nodes, (a, b)
        self.edges.setdefault((a, b), kind)

    def chain(self, floor, keys):
        for a, b in zip(keys, keys[1:]):
            self.edge(f"{BUILDING}-{floor}-{a}", f"{BUILDING}-{floor}-{b}")

    def room(self, floor, name, center, door, attach):
        d = self.node(floor, f"V{door[0]}_{door[1]}", *door, kind="VRATA")
        r = self.node(floor, name, *center, kind="PROSTORIJA", name=name)
        self.edge(r, d)
        self.edge(d, f"{BUILDING}-{floor}-{attach}")


def points(g, floor, spec):
    """spec: {"ključ": (x, y)} - čvorovi hodnika."""
    for key, (x, y) in spec.items():
        g.node(floor, key, x, y)


def typical_plan(f):
    """Crtež II-IV sprata: zajednička geometrija + pregrade podeljenih soba i spojene sobe tog sprata."""
    rooms = list(TYPICAL["rooms"])
    if f == 3:  # korisnik (04.10.2026): "može se uraditi merge prostorije NTP-312 i neobeležene prostorije ispod nje"
        rooms[11] = [(451, 1000), (666, 1000), (666, 1280), (451, 1280)]
        rooms[12] = None
    for room in SPLIT.get(f, ()):
        rooms[room] = None
        rooms += SPLIT_SHAPES[room]
    return {**TYPICAL, "rooms": [r for r in rooms if r]}


def plan_for(f):
    return GROUND if f == 0 else FIRST if f == 1 else TOP if f == 5 else typical_plan(f)


def typical_floor(g, f):
    # I sprat: gornji hodnik je niži (FIRST), pa i njegovi čvorovi.
    top = {f"U{x}": (x, 636) for x in (690, 782, 882, 982, 1040)} if f == 1 else \
        {f"U{x}": (x, 595) for x in (715, 817, 921, 1023)}
    points(g, f, {
        **{f"M{y}": (400, y) for y in (450, 595, 676, 736, 927, 1112, 1250, 1334, 1470, 1608)},
        "U640": (640, 595), **top,
        **{f"R{y}": (1350, y) for y in (830, 930, 1160)},
        **{f"C{x}": (x, 1334) for x in (560, 815, 870, 983, 1170, 1350, 1494, 1600, 1795)},
        "K1350": (1350, 1470), "K1795": (1795, 1470),
        **{f"B{x}": (x, 1608) for x in OFFICES[1:]},
    })
    g.chain(f, [f"M{y}" for y in (450, 595, 676, 736, 927, 1112, 1250, 1334, 1470, 1608)])
    g.chain(f, ["M595", "U640", *sorted(top, key=lambda k: top[k][0])])
    g.chain(f, ["M1334", "C560", "C815", "C870", "C983", "C1170", "C1350", "C1494", "C1600", "C1795"])
    g.chain(f, ["R830", "R930", "R1160", "C1350", "K1350", "B1343"])
    g.chain(f, ["C1795", "K1795", "B1819"])
    g.chain(f, ["M1608", *[f"B{x}" for x in OFFICES[1:]]])
    g.edge(g.node(f, "S1", 530, 445, "STEPENISTE"), f"NTP-{f}-M450")
    g.edge(g.node(f, "S2", 575, 1470, "STEPENISTE"), f"NTP-{f}-M1470")
    g.edge(g.node(f, "S3", 1670, 1470, "STEPENISTE"), f"NTP-{f}-C1600")
    g.edge(g.node(f, "L1", *LIFTS["L1"], "LIFT"), f"NTP-{f}-U640")
    g.edge(g.node(f, "L2", *LIFTS["L2"], "LIFT"), f"NTP-{f}-M1470")
    g.edge(g.node(f, "L3", *LIFTS["L3"], "LIFT"), f"NTP-{f}-C1494")
    for room, numbers in TYPICAL_LABELS[f]:
        numbers = numbers if isinstance(numbers, tuple) else (numbers,)
        if room == 17 and f in VIA_FIRST:
            # Vrata iz hodnika vode u prvu polovinu (prolaz P17), a iz nje vrata u pregradi u drugu.
            _, door, attach = SPLIT_DOORS[17][0]
            d = g.node(f, f"V{door[0]}_{door[1]}", *door, kind="VRATA")
            p = g.node(f, "P17", *VIA_17["pass"])
            g.edge(f"{BUILDING}-{f}-{attach}", d)
            g.edge(d, p)
            g.edge(g.node(f, f"NTP-{numbers[0]}", *VIA_17["first"], kind="PROSTORIJA", name=f"NTP-{numbers[0]}"), p)
            g.room(f, f"NTP-{numbers[1]}", VIA_17["second"], VIA_17["door"], "P17")
            continue
        if room == 17 and f == 1:
            # 124: iz T3 (soba bez oznake), a u T3 sa kraja srednjeg hodnika.
            d = g.node(f, "V{}_{}".format(*FIRST_T3["door"]), *FIRST_T3["door"], kind="VRATA")
            t3 = g.node(f, "T3", *FIRST_T3["pass"])
            g.edge(f"{BUILDING}-{f}-C1795", d)
            g.edge(d, t3)
            g.room(f, f"NTP-{numbers[0]}", *FIRST_T3["room17"], "T3")
            continue
        if room in SPLIT.get(f, ()):
            for number, (center, door, attach) in zip(numbers, SPLIT_DOORS[room]):
                g.room(f, f"NTP-{number}", center, door, attach)
            continue
        if room == "above":
            spec = ABOVE_RIGHT
        elif room == "corner":
            spec = CORNER_419
        elif f == 1 and room in FIRST_DOORS:
            spec = FIRST_DOORS[room]
        elif f == 3 and room == 11:  # spojena sa sobom ispod (typical_plan)
            spec = ((559, 1140), *TYPICAL_DOORS[11][1:])
        else:
            spec = TYPICAL_DOORS[room]
        g.room(f, f"NTP-{numbers[0]}", *spec)


def top_floor(g):
    f = 5
    points(g, f, {
        **{f"M{y}": (400, y) for y in (450, 595, 676, 890, 1112, 1250, 1334, 1470)},
        **{f"U{x}": (x, 595) for x in (640, 817, 920)},
        **{f"C{x}": (x, 1334) for x in (560, 815, 870, 1025, 1115, 1212)},
        **{f"R{y}": (1115, y) for y in (856, 945, 1035, 1130, 1230)},
        # terasa do desnog jezgra - evakuacioni put, ne vezuje se za hodnik (korisnik, 02.10.2026); jezgro se
        # na V spratu dostiže samo stepenicama/liftom odozdo
        "T1350": (1350, 1340), "T1494": (1494, 1340), "T1600": (1600, 1340),
    })
    g.chain(f, [f"M{y}" for y in (450, 595, 676, 890, 1112, 1250, 1334, 1470)])
    g.chain(f, ["M595", "U640", "U817", "U920"])
    g.chain(f, ["M1334", "C560", "C815", "C870", "C1025", "C1115", "C1212"])
    g.chain(f, ["T1350", "T1494", "T1600"])
    g.chain(f, ["R856", "R945", "R1035", "R1130", "R1230", "C1115"])
    g.edge(g.node(f, "S1", 530, 445, "STEPENISTE"), "NTP-5-M450")
    g.edge(g.node(f, "S2", 575, 1470, "STEPENISTE"), "NTP-5-M1470")
    g.edge(g.node(f, "S3", 1680, 1470, "STEPENISTE"), "NTP-5-T1600")
    g.edge(g.node(f, "L1", *LIFTS["L1"], "LIFT"), "NTP-5-U640")
    g.edge(g.node(f, "L2", *LIFTS["L2"], "LIFT"), "NTP-5-M1470")
    g.edge(g.node(f, "L3", *LIFTS["L3"], "LIFT"), "NTP-5-T1494")
    for number, center, door, attach in TOP_LABELS.values():
        g.room(f, f"NTP-{number}", center, door, attach)


def ground_floor(g):
    f = 0
    points(g, f, {
        "V0": (0, 120), "V1": (ULAZ_FTN[0], 120), **{f"H{y}": (290, y) for y in (160, 450, 598, 700, 1000, 1250, 1340)},
        **{f"M{y}": (400, y) for y in (1340, 1470, 1620, 1782)},
        "VA": (-275, 120),  # zapadni kraj predvorja "ULAZ - FTN", ka NTP-A
        "VB": (-600, 320),  # ispred južnog zida NTP-A
        "U560": (560, 598), "U690": (690, 598), "P740": (690, 740), "P865": (690, 865),
        "W900": (900, 865), "PA": (1200, 760), "PB": (1250, 865), "PC": (1250, 1100),
        **{f"P{y}": (1350, y) for y in (1300, 1420, 1690)},
        **{f"LB{x}": (x, 1340) for x in (1494, 1600, 1790)},
        **{f"W{x}": (x, 1340) for x in (143, 0, -130)},
        **{f"BB{x}": (x, 1782) for x in (510, 630, 750)},
    })
    for key, ((x, y), _) in ENTRANCES.items():
        g.node(f, key, x, y, "ULAZ")
    g.chain(f, ["ULAZ-FTN", "V1", "V0", "H160", "H450", "H598", "H700", "H1000", "H1250", "H1340", "M1340", "M1470", "M1620",
                "M1782", "ULAZ"])
    g.chain(f, ["H1340", "W143", "W0", "W-130"])
    g.chain(f, ["V1", "VA", "VB"])
    g.chain(f, ["H598", "U560", "U690", "P740", "P865", "W900", "PB"])
    g.chain(f, ["ULAZ-PASAZ-Z", "PA", "PB", "PC", "P1300", "P1420", "P1690", "ULAZ-PASAZ-I"])
    g.chain(f, ["P1300", "LB1494", "LB1600", "LB1790"])
    g.chain(f, ["M1782", "BB510", "BB630", "BB750"])
    g.edge(g.node(f, "S1", 530, 445, "STEPENISTE"), "NTP-0-H450")
    g.edge(g.node(f, "S2", 606, 1473, "STEPENISTE"), "NTP-0-M1470")
    g.edge(g.node(f, "S3", 1678, 1468, "STEPENISTE"), "NTP-0-LB1600")
    g.edge(g.node(f, "L1", *LIFTS["L1"], "LIFT"), "NTP-0-U560")
    g.edge(g.node(f, "L2", *LIFTS["L2"], "LIFT"), "NTP-0-M1470")
    g.edge(g.node(f, "L3", *LIFTS["L3"], "LIFT"), "NTP-0-LB1494")
    # NTP-A: poslovni deo. Korisnik (teren 03.10.2026): vrata "na kraju", upravno na vrata 001, na zidu suprotnom od
    # najbližeg zida zgrade - najbliži je severni (1-4 m od OSM obrisa) -> južni zid, istočni kraj (bliže izlazu).
    # Put od predvorja do vrata nije na planu (poslovni deo) - PRETPOSTAVKA.
    center = (sum(x for x, _ in NTP_A) / 4, sum(y for _, y in NTP_A) / 4)
    g.room(f, "NTP-A", center, (-680, NTP_A[1][1]), "VB")
    g.room(f, "NTP-L3", (930, 650), (1131, 764), "PA")  # "LAB" - pretpostavka
    # Na planu prizemlja piše L2, a na vratima C (korisnik, teren 03.10.2026).
    g.room(f, "NTP-C", (1170, 1410), (1299, 1420), "P1420")
    g.room(f, "NTP-L1", (1170, 1690), (1299, 1690), "P1690")
    g.room(f, "NTP-L4", (1580, 1150), (1580, 1297), "LB1600")
    # Teren 03.10.2026 (korisnik): 001 je soba "AMFITEATAR" sa plana (učionica; vrata bliže stepeništu S2 -
    # PRETPOSTAVKA: na zidu ka holu, donji kraj), 003 prva od tri male sobe ispod S2, 004 soba desno od L4.
    g.room(f, "NTP-001", (552, 1094), (450, 1250), "H1250")
    g.room(f, "NTP-003", (510, 1645), (510, 1740), "BB510")
    g.room(f, "NTP-004", (1930, 1331), (1780, 1360), "LB1790")
    # NTP-005 nije nađen na terenu - izmišljeno mesto u levom krilu.
    g.room(f, "NTP-005", (143, 1480), (143, 1407), "W143")


def build():
    g = Graph()
    ground_floor(g)
    for f in (1, 2, 3, 4):
        typical_floor(g, f)
    top_floor(g)
    for f in range(5):
        for s in STAIRS:
            g.edge(f"NTP-{f}-{s}", f"NTP-{f + 1}-{s}", "STEPENICE")
    for lift in LIFTS:
        for a in FLOORS:
            for b in FLOORS:
                if a < b:
                    g.edge(f"NTP-{a}-{lift}", f"NTP-{b}-{lift}", "LIFT")
    return g


# --- Izlaz ------------------------------------------------------------------------------------

def rel(x, y):
    return round((x - VX) / VW, 5), round((y - VY) / VH, 5)


def write_json(g, path):
    data = {
        "buildingId": BUILDING,
        "viewport": [VX, VY, VW, VH],
        "floors": list(FLOORS),
        "entranceId": f"{BUILDING}-0-{MAIN_ENTRANCE}",
        "nodes": [
            {"id": nid, "floor": f, "x": rel(x, y)[0], "y": rel(x, y)[1], "type": kind, **({"name": name} if name else {})}
            for nid, (f, x, y, kind, name) in g.nodes.items()
        ],
        "edges": [[a, b, kind] for (a, b), kind in g.edges.items()],
        "campusLinks": [[campus, f"{BUILDING}-0-{key}"] for key, (_, campus) in ENTRANCES.items()],
    }
    path.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


C_BG, C_ROOF, C_CORRIDOR, C_STAIRS, C_LIFT = "#FFFFFFFF", "#FFF0F0F0", "#FFE8EEF4", "#FFFFE0B2", "#FFFFCC80"
C_ENTRANCE, C_WALL, C_OUTER, C_TREAD, C_TERRACE = "#FFC8E6C9", "#FF455A64", "#FF263238", "#FF8D6E63", "#FFDCEDC8"


def poly(pts):
    return "M" + " L".join(f"{x:g},{y:g}" for x, y in pts) + " Z"


def rect(x0, y0, x1, y1):
    return poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])


def treads(x0, y0, x1, y1, step=22):
    """Gazišta: linije popreko duže strane stepeništa."""
    if x1 - x0 >= y1 - y0:
        return " ".join(f"M{x},{y0} V{y1}" for x in range(int(x0) + step, int(x1), step))
    return " ".join(f"M{x0},{y} H{x1}" for y in range(int(y0) + step, int(y1), step))


def hatch(x0, y0, x1, y1, step=40):
    """Kose linije (rampa, terasa)."""
    out = []
    for k in range(int(x0 - (y1 - y0)), int(x1), step):
        a = (max(k, x0), y0 + max(0, x0 - k))
        b = (min(k + (y1 - y0), x1), y0 + min(y1 - y0, x1 - k))
        if b[0] > a[0]:
            out.append(f"M{a[0]:g},{a[1]:g} L{b[0]:g},{b[1]:g}")
    return " ".join(out)


def path(fill=None, stroke=None, width=None, data=""):
    attrs = [f'android:pathData="{data}"']
    if fill:
        attrs.insert(0, f'android:fillColor="{fill}"')
    if stroke:
        attrs.insert(0, f'android:strokeColor="{stroke}"')
        attrs.insert(1, f'android:strokeWidth="{width}"')
    return "        <path\n            " + "\n            ".join(attrs) + " />\n"


def drawable(plan, title):
    parts = [("Ceo NTP (OSM obris, poslovni deo nije ucrtan)",
              path(fill="#FFF5F5F5", stroke="#FFBDBDBD", width=4, data=poly(OSM_OUTLINE)))]
    if plan.get("business_rooms"):
        parts.append(("Poslovni deo: NTP-A", path(fill=C_BG, stroke=C_WALL, width=4,
                                                    data=" ".join(poly(p) for p in plan["business_rooms"]))))
    if "roof" in plan:
        parts.append(("Krov (niži spratovi)", path(fill=C_ROOF, stroke="#FFBDBDBD", width=4, data=poly(plan["roof"]))))
    outlines = [plan["outline"]] + ([plan["outline2"]] if "outline2" in plan else [])
    parts.append(("Unutrašnjost", path(fill=C_BG, data=" ".join(poly(o) for o in outlines))))
    if plan.get("terraces"):
        t = plan["terraces"]
        parts.append(("Terase (evakuacioni put po krovu)", path(fill=C_TERRACE, data=" ".join(poly(p) for p in t))))
    parts.append(("Hodnici", path(fill=C_CORRIDOR, data=" ".join(poly(p) for p in plan["corridors"]))))
    for x0, y0, x1, y1 in plan.get("ramps", []):
        parts.append(("Rampa", path(fill="#FFEEEEEE", data=rect(x0, y0, x1, y1))
                      + path(stroke=C_TREAD, width=2, data=hatch(x0, y0, x1, y1))))
    if plan.get("entrances"):
        parts.append(("Ulazi", path(fill=C_ENTRANCE, data=" ".join(rect(*e) for e in plan["entrances"]))))
    parts.append(("Stepeništa", path(fill=C_STAIRS, data=" ".join(rect(*s) for s in plan["stairs"]))
                  + path(stroke=C_TREAD, width=2, data=" ".join(treads(*s) for s in plan["stairs"]))))
    parts.append(("Šahtovi", path(fill="#FFB0BEC5", data=" ".join(rect(*s) for s in plan["shafts"]))))
    parts.append(("Liftovi", path(fill=C_LIFT, stroke=C_WALL, width=4, data=" ".join(rect(*l) for l in plan["lifts"]))
                  + path(stroke=C_WALL, width=2, data=" ".join(
                      f"M{x0},{y0} L{x1},{y1} M{x1},{y0} L{x0},{y1}" for x0, y0, x1, y1 in plan["lifts"]))))
    parts.append(("Prostorije", path(stroke=C_WALL, width=4, data=" ".join(poly(p) for p in plan["rooms"]))))
    parts.append(("Spoljni zid", path(stroke=C_OUTER, width=9, data=" ".join(poly(o) for o in outlines))))
    body = "".join(f"\n        <!-- {name} -->\n{p}" for name, p in parts)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!--
    {title} - Naučno-tehnološki park (FTN deo). GENERISANO skriptom tools/ntp/build_ntp.py
    (ne menjati ručno). Šematski precrtano sa ispravljenih fotografija evakuacionih planova;
    koordinate su u zajedničkom sistemu spratova, grupa ih pomera u viewport.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{VW // 2}dp"
    android:height="{VH // 2}dp"
    android:viewportWidth="{VW}"
    android:viewportHeight="{VH}">

    <group
        android:translateX="{-VX}"
        android:translateY="{-VY}">
{body}
    </group>
</vector>
"""


def check_images(g, folder):
    from PIL import Image, ImageDraw
    colors = {"HOD": (0, 90, 255), "STEPENICE": (255, 0, 0), "LIFT": (255, 0, 0)}
    for f in FLOORS:
        plan = plan_for(f)
        img = Image.open(folder / f"w{f}.png").convert("RGB")
        d = ImageDraw.Draw(img, "RGBA")
        P = lambda p: (p[0] + 300, p[1])
        for c in plan["corridors"]:
            d.polygon([P(p) for p in c], fill=(0, 120, 255, 40), outline=(0, 90, 255))
        for r in plan["rooms"]:
            d.polygon([P(p) for p in r], outline=(120, 0, 160))
        for o in [plan["outline"]] + ([plan["outline2"]] if "outline2" in plan else []):
            d.line([P(p) for p in o + [o[0]]], fill=(0, 0, 0), width=5)
        for x0, y0, x1, y1 in plan["stairs"] + plan["lifts"]:
            d.rectangle([P((x0, y0)), P((x1, y1))], outline=(255, 120, 0), width=4)
        for (a, b), kind in g.edges.items():
            na, nb = g.nodes[a], g.nodes[b]
            if na[0] == nb[0] == f:
                d.line([P(na[1:3]), P(nb[1:3])], fill=colors[kind], width=4)
        for nid, (fl, x, y, kind, name) in g.nodes.items():
            if fl != f:
                continue
            x, y = P((x, y))
            r = 12 if kind in ("PROSTORIJA", "STEPENISTE", "LIFT", "ULAZ") else 6
            d.ellipse((x - r, y - r, x + r, y + r), fill=(255, 0, 0) if kind != "PROSTORIJA" else (0, 160, 0))
            if name:
                d.text((x + 14, y - 6), name, fill=(0, 100, 0))
        img.resize((img.width // 2, img.height // 2)).save(folder / f"check{f}.png")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", type=Path, help="folder sa w0..w5.png (fotografije u zajedničkom sistemu)")
    args = parser.parse_args()
    g = build()
    res = ROOT / "app/src/main/res/drawable"
    titles = ["Prizemlje", "I sprat", "II sprat", "III sprat", "IV sprat", "V sprat"]
    for f in FLOORS:
        (res / f"floor_plan_ntp_{f}.xml").write_text(drawable(plan_for(f), titles[f]), encoding="utf-8")
    # Do 04.10.2026 su I-IV bili jedan crtež.
    (res / "floor_plan_ntp_typical.xml").unlink(missing_ok=True)
    write_json(g, ROOT / "app/src/main/assets/ntp.json")
    rooms = sum(1 for n in g.nodes.values() if n[3] == "PROSTORIJA")
    print(f"NTP: {len(g.nodes)} čvorova, {len(g.edges)} ivica, {rooms} sala")
    if args.check:
        check_images(g, args.check)


if __name__ == "__main__":
    main()
