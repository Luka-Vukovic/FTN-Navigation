"""
Amfiteatri (AMF, u FtnGO-u "Nastavni blok sa amfiteatrima"): crteži nivoa -1, 0 i 1 i unutrašnji graf.

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/zgrade/build_amf.py [--check <folder sa w-1, w0.png>]

Pravi res/drawable/floor_plan_amf_{m1,0,1}.xml i assets/amf.json (isti format kao nb.json).

Izvor: snimci FtnGO-a (images/amf-1.png, amf0.png; amf1.png je skoro isti kao prizemlje - gornji deo
amfiteatara - i ne crta se) i fotografije iz virtuelne ture FTN-a (images/vk nb to amf*.png, amf
stepenište.png, kula spaja nb i amf.png; korisnik, 30.09.2026):
  - glavni ulaz je na -1 (stakleni ulaz gore levo, kod "FTN Student"); široko stepenište u sredini
    zapadnog zida (dva kraka) vodi u prizemlje; drugo stepenište je dole levo, kod trema ka Kuli,
  - stakleni prolaz iz NB-a se završava stepenicama naniže do hodnika iza A1/A2 ("Amphitheaters A1 A2
    A4"; u turi "Amfiteatri suteren") - na FtnGO-u je to hodnik ispod A2 u prizemlju, pa je tako i ovde.
    A1, A2 i A4 imaju zadnja vrata na taj hodnik, A1 i A2 i gornja na glavni hodnik prizemlja,
  - INT 1 (korisnik, 30.09.2026): niz stepenice (široko S2) na -1, preko puta A1 i A2, "odmah pored GRID 1,
    nisu direktno povezani" - neoznačena soba levo od GRID-1 na FtnGO -1, sa svojim vratima na hodnik,
  - stepenice NAVIŠE na kraju prolaza iz NB-a vode u L1 ("po oznaci na vratima", korisnik) - prostorija
    iznad zadnjeg hodnika, na FtnGO nivou 1 (jedino što se na tom nivou razlikuje od prizemlja),
  - glavni ulaz vodi na nivo sa Skriptarnicom i Bibliotekom (-1), ulaz kod GRID-a do GRID laboratorija
    (korisnik); trem iz Kule stiže na međunivo donjeg levog stepeništa S1, odakle se ide dole na -1 ili gore
    u prizemlje (teren 01.10.2026; veza sa oba nivoa - prelaz -1 <-> 0 preko trema nema cenu stepenica),
    prolazi ka F-bloku i ITC-u su u prizemlju ("amf to f.png": prolaz je iznad Skriptarnice). Prolaz ka
    F-bloku stiže na međunivo F-bloka (dole prizemlje, gore I sprat) - F-blok nema plan, pa se to ne vidi,
  - GRID-1 i GRID-2 su na -1; GRID ima svoj ulaz sa zapada (korisnik), koji vodi kroz GRID-1.
Teren 02.10.2026 (korisnik + evakuacioni plan prizemlja, teren/izveštaj2/): gornji red prizemlja je
"AR0 AR1 X AR2 AR3 X AR4 | stepenište | AR5 X X ? AR6" (X = ne zna se šta je; AR6 je velika soba na kraju, ka
ITC-u) - pregrade sa evakuacionog plana (FtnGO je imao 7 soba jednu do druge). Kiosk (radnim danom 7-18) je na
-1 odmah pored stepeništa S1, desno od njega (korisnik označio na snimku ekrana).
Na -1 u sredini (ispod amfiteatara) nema prostorija. Pošta (Pošta Srbije, ulaz spolja sa zapada) nije u ovom
grafu - na mapi kampusa je služba (build_campus.py SERVICES).
"""

import argparse
from pathlib import Path

from common import STEPS, TOALET, Flights, R, build_graph, check_images, col, rect_poly, row, write_all

VX, VY, VW, VH = VIEWPORT = (30, -80, 820, 310)
WALL = (168, -65, 835, 210)  # OSM obris u zajedničkom sistemu (67,7 x 27,9 m)
BUILDING = "AMF"

# Stepeništa po evakuacionom planu prizemlja (teren/izveštaj2/IMG_20261002_103156.jpg; teren 05.10.2026: "nije dobra
# pozicija stepenica na AMF prizemlju"). Plan je u istoj orijentaciji kao ovaj; razmera ~0,78 px plana po px fotografije.
#
# S1 (dole levo, kod trema ka Kuli): preko cele širine hodnika tri kraka. Sa podesta na početku trema (međunivo, teren
# 01.10.2026) dva bočna kraka vode gore u prizemlje (ka severu), a srednji dole u suteren (ka severu, ispod hodnika
# prizemlja). "amf stepenište.png" (virtuelna tura) je baš ovo stepenište, snimljeno sa podesta - do 05.10.2026 je
# pogrešno pripisano S2, a S1 je bio kvadrat 3 x 3 m na sredini hodnika. Na -1 ispod desnog bočnog kraka je Kiosk, ispod
# levog soba. Putanja: dno srednjeg kraka (-1) -> podest -> bočni krak -> vrh bočnog kraka (prizemlje); dve varijante,
# levim ili desnim bočnim krakom (bira strana okreta na podestu). Trem iz Kule stiže na podest.
S1_MID, S1_LEFT, S1_RIGHT, S1_LANDING = (234, 172, 265, 205), (212, 172, 233, 205), (266, 172, 287, 205), (212, 205, 287, 213)
S1_PATH_R = [(250, 174), (250, 209), (277, 209), (277, 174)]
S1_PATH_L = [(250, 174), (250, 209), (222, 209), (222, 174)]
S1 = ("S1", None, S1_PATH_R[0])
S1_FLIGHTS_M1 = [Flights(S1_MID, S1_RIGHT, S1_LANDING, S1_PATH_R, draw_down=False),
                 Flights(S1_MID, S1_LEFT, S1_LANDING, S1_PATH_L, draw_down=False)]
# Čvor S1-D (prizemlje) je gde se hodnik spaja sa oba bočna kraka - hodnik vodi pravo na krakove (replay 04.10.: AMF -> Kula).
S1_TOP = (250, 172)
# Prizemlje (teren 05.10.2026, korisnik + "amf stepenice kod citaonice.png"): sa kraja hodnika levi i desni krak naniže uz
# zidove, između njih ograda nad praznim prostorom (dole stakleni trem) - srednji krak je ispod galerije, u prizemlju se ne crta.
S1_VOID = S1_MID
S1_FLIGHTS_0 = [Flights(S1_MID, S1_RIGHT, S1_LANDING, S1_PATH_R, down_node=S1_TOP, draw_up=False),
                Flights(S1_MID, S1_LEFT, S1_LANDING, S1_PATH_L, down_node=S1_TOP, draw_up=False)]
# Trem iz Kule: sa podesta S1 (na -1 uz srednji krak, u prizemlju niz bočni krak).
# Krakovi do podesta su HOD ivice sa stepenicima (STEPS) - ruta "bez stepenica" ne ide tuda.
KULA_VIA_M1 = ("S1", [("PODEST-S1", (250, 207), STEPS)])
KULA_VIA_0 = ("S1-D", [("S1-D-KRAK", (277, 176)), ("PODEST-S1-D", (277, 207), STEPS), ("PODEST-S1", (250, 207))])
PASSAGE_KULA = (250, 214)

# S2 (uz zapadni zid, između AR4 i AR5): pravo stepenište - dva kraka u nizu sa podestom između. Iz prizemlja se ulazi
# sleva (niša pored AR4) i silazi nadesno; dole (-1) desni kraj, uz evakuacioni izlaz. Bez okreta - sprat se menja
# pređenim putem (PdrLocator, pravo stepenište).
S2_PATH = [(584, -42), (556, -42), (548, -42), (521, -42)]
S2_FLIGHTS = Flights(up=(556, -53, 587, -31), down=(518, -53, 548, -31), landing=(548, -53, 556, -31), path=S2_PATH)
S2 = ("S2", None, S2_PATH[0])

# Kraj staklenog prolaza iz NB-a (evakuacioni plan): širi krak niz prolaz u hodnik iza amfiteatara (deo prolaza - crtež)
# i uži krak desno od njega, naviše - u L1 (korisnik: "po oznaci na vratima").
S3 = ("S3", (688, 226, 704, 247), (696, 228))
NB_PASSAGE_STEPS = (670, 226, 688, 264)
CAMPUS_LINKS = [
    ("K-U-AMF-1", "AMF-m1-ULAZ"),
    ("K-U-AMF-2", "AMF-m1-ULAZ-GRID"),
    # Trem iz Kule stiže na međunivo stepeništa S1 (teren 01.10.2026): odatle dole na -1 (izlaz, Biblioteka,
    # Skriptarnica) ili gore na prizemlje (amfiteatri) - zato veza sa oba nivoa.
    ("K-P-AMF-KULA", "AMF-m1-PROLAZ-KULA"),
    ("K-P-AMF-KULA", "AMF-0-PROLAZ-KULA"),
    ("K-P-AMF-NB", "AMF-0-PROLAZ-NB"),
    ("K-P-AMF-F", "AMF-0-PROLAZ-F"),
    ("K-P-ITC-AMF", "AMF-0-PROLAZ-ITC"),
]


def floor_m1():
    return {
        "title": "Suteren (-1)",
        # Sredina (ispod amfiteatara) nije deo sprata; Skriptarnica izlazi levo, ka F-bloku.
        "outline": [[(168, -65), (835, -65), (835, 210), (732, 210), (732, 3), (402, 3), (402, 210), (168, 210)],
                    rect_poly(30, -30, 168, 0)],
        "paths": [[(175, -7), (825, -7)], [(257, -7), (257, 170)]],  # hodnik do dna srednjeg kraka S1
        "corridors": [rect_poly(170, -17, 832, 3), rect_poly(203, -65, 283, -17), rect_poly(230, 3, 285, 207),
                      rect_poly(518, -31, 590, -17)],  # ispod S2
        "rooms": [
            R(30, 170, -30, 0, "Skriptarnica", door=(170, -10)),  # FtnGO "Skriptarnica (B015)"
            R(170, 203, -65, -30, "FTN Student"),
            *row(-65, -17, [(285, 317, "B001"), (317, 347, "B002"), (347, 378, "B003"), (378, 410, "B004"),
                            (410, 430, "B005"), (430, 443, "B006"), (443, 480, "B007"),
                            (587, 660, "INT 1"), (660, 765, "GRID-1"), (800, 832, None)]),
            # Toaleti (ikonice M i Ž na FtnGO-u): pored B007 i pored GRID-1.
            R(480, 518, -65, -17, amenity=TOALET), R(765, 800, -65, -17, amenity=TOALET),
            R(170, 230, 0, 170, "Scen-LAB", door=(230, 60)),  # FtnGO "SCENLab"
            R(170, 205, 170, 207), R(205, 230, 170, 207),
            R(266, 285, 183, 207, "Kiosk"),  # pored stepeništa S1 (teren 02.10.2026)
            R(285, 402, 3, 43, "B008"),
            R(285, 402, 43, 207, "Biblioteka", door=(285, 120)),  # FtnGO "Biblioteka (B009)"
            R(732, 832, 3, 207, "GRID-2", door=(760, 3)),
        ],
        "stairs": [S1, S2],
        "flights": {"S1": S1_FLIGHTS_M1, "S2": S2_FLIGHTS},
        "points": [("ULAZ", (245, -65), "ULAZ"), ("ULAZ-GRID", (723, -65), "ULAZ"),
                   ("PROLAZ-KULA", PASSAGE_KULA, "PROLAZ", KULA_VIA_M1)],
    }


def floor_0():
    return {
        "title": "Prizemlje",
        "paths": [[(175, -10), (825, -10)], [(250, -10), (250, 170)], [(555, 188), (690, 188)]],  # do vrha krakova S1
        "corridors": [rect_poly(170, -28, 832, 7), rect_poly(212, 7, 287, 172),  # hodnik do ograde iznad S1
                      rect_poly(30, -32, 170, 5),  # prolaz ka F-bloku
                      rect_poly(553, 168, 693, 207),  # hodnik iza amfiteatara, iz prolaza iz NB-a
                      rect_poly(667, 207, 704, 265),  # kraj staklenog prolaza iz NB-a (evakuacioni plan)
                      rect_poly(497, -53, 518, -28)],  # niša ispred S2
        "rooms": [
            # Evakuacioni plan (teren 02.10.2026): 7 soba levo od stepeništa S2, 5 desno; None = ne zna se šta je.
            *row(-65, -28, [(170, 259, "AR0"), (259, 295, "AR1"), (295, 331, None), (331, 386, "AR2"),
                            (386, 420, "AR3"), (420, 452, None), (452, 497, "AR4"),
                            (587, 630, "AR5"), (630, 665, None), (665, 700, None), (700, 735, None),
                            (735, 832, "AR6")]),
            *col(170, 212, [(7, 48, None), (48, 89, None), (89, 130, None), (130, 207, None)]),
            R(287, 402, 7, 207, "Čitaonica", door=(287, 50)),  # FtnGO "Čitaonica (A0)"
            R(402, 545, 7, 207, "A1", doors=[(470, 7), (545, 188)]),  # Amfiteatar "Nikola Tesla"
            R(545, 693, 7, 168, "A2", doors=[(620, 7), (650, 168)]),  # Amfiteatar "Milutin Milanković"
            R(693, 832, 7, 110, "A3"),
            R(693, 832, 110, 207, "A4", door=(693, 188)),
        ],
        "stairs": [S1, S2, S3],
        "flights": {"S1": S1_FLIGHTS_0, "S2": S2_FLIGHTS},
        "steps": [NB_PASSAGE_STEPS],
        "voids": [S1_VOID],  # iza ograde na kraju hodnika (ispod: podest i srednji krak S1)
        "points": [("PROLAZ-F", (40, -13), "PROLAZ"), ("PROLAZ-ITC", (832, -10), "PROLAZ"),
                   ("PROLAZ-NB", (680, 207), "PROLAZ"), ("PROLAZ-KULA", PASSAGE_KULA, "PROLAZ", KULA_VIA_0)],
    }


def floor_1():
    return {
        "title": "Nivo 1 (L1, iznad hodnika iza amfiteatara)",
        "roof": rect_poly(*WALL),
        "outline": [rect_poly(553, 168, 711, 207)],
        "paths": [[(680, 195), (700, 212)]],
        "corridors": [rect_poly(664, 190, 711, 240)],
        "rooms": [R(553, 664, 168, 207, "L1", door=(664, 195))],
        "stairs": [S3],
    }


PLANS = {-1: floor_m1(), 0: floor_0(), 1: floor_1()}


def build():
    return build_graph(BUILDING, PLANS, stairs={"S1": [-1, 0], "S2": [-1, 0], "S3": [0, 1]}, lifts={})


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", type=Path, help="folder sa w-1.png, w0.png, w1.png (prepare_screens.py amf)")
    args = parser.parse_args()
    g = build()
    write_all(g, PLANS, VIEWPORT, WALL, "Amfiteatri", "build_amf.py", "floor_plan_amf", "AMF-m1-ULAZ", CAMPUS_LINKS)
    if args.check:
        check_images(g, PLANS, VIEWPORT, WALL, args.check)


if __name__ == "__main__":
    main()
