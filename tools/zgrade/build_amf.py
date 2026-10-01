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
Na -1 u sredini (ispod amfiteatara) nema prostorija. Pošta (Pošta Srbije, ulaz spolja sa zapada) nije u ovom
grafu - na mapi kampusa je služba (build_campus.py SERVICES).
"""

import argparse
from pathlib import Path

from common import R, build_graph, check_images, col, rect_poly, row, write_all

VX, VY, VW, VH = VIEWPORT = (30, -80, 820, 310)
WALL = (168, -65, 835, 210)  # OSM obris u zajedničkom sistemu (67,7 x 27,9 m)
BUILDING = "AMF"

S1 = ("S1", (232, 177, 262, 207), (247, 190))  # dole levo, kod trema ka Kuli
S3 = ("S3", (693, 212, 711, 240), (700, 212))  # sa kraja prolaza iz NB-a naviše, u L1
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
        "paths": [[(175, -7), (825, -7)], [(257, -7), (257, 200)]],
        "corridors": [rect_poly(170, -17, 832, 3), rect_poly(203, -65, 283, -17), rect_poly(230, 3, 285, 207)],
        "rooms": [
            R(30, 170, -30, 0, "Skriptarnica", door=(170, -10)),  # FtnGO "Skriptarnica (B015)"
            R(170, 203, -65, -30, "FTN Student"),
            *row(-65, -17, [(285, 317, "B001"), (317, 347, "B002"), (347, 378, "B003"), (378, 410, "B004"),
                            (410, 430, "B005"), (430, 443, "B006"), (443, 480, "B007"), (480, 518, None),
                            (587, 660, "INT 1"), (660, 765, "GRID-1"), (765, 800, None), (800, 832, None)]),
            R(170, 230, 0, 170, "Scen-LAB", door=(230, 60)),  # FtnGO "SCENLab"
            R(170, 205, 170, 207), R(205, 230, 170, 207),
            R(285, 402, 3, 43, "B008"),
            R(285, 402, 43, 207, "Biblioteka", door=(285, 120)),  # FtnGO "Biblioteka (B009)"
            R(732, 832, 3, 207, "GRID-2", door=(760, 3)),
        ],
        "stairs": [S1, ("S2", (518, -65, 587, -40), (552, -35))],
        "points": [("ULAZ", (245, -65), "ULAZ"), ("ULAZ-GRID", (723, -65), "ULAZ"),
                   ("PROLAZ-KULA", (257, 210), "PROLAZ")],
    }


def floor_0():
    return {
        "title": "Prizemlje",
        "paths": [[(175, -10), (825, -10)], [(250, -10), (250, 200)], [(555, 188), (690, 188)]],
        "corridors": [rect_poly(170, -28, 832, 7), rect_poly(212, 7, 287, 207),
                      rect_poly(30, -32, 170, 5),  # prolaz ka F-bloku
                      rect_poly(553, 168, 693, 207),  # hodnik iza amfiteatara, iz prolaza iz NB-a
                      rect_poly(664, 207, 711, 240)],  # kraj staklenog prolaza iz NB-a (OSM spojni deo)
        "rooms": [
            *row(-65, -28, [(170, 258, "AR0"), (258, 327, "AR1"), (327, 410, "AR2"), (410, 497, "AR3"),
                            (587, 657, "AR4"), (657, 726, "AR5"), (726, 832, "AR6")]),
            *col(170, 212, [(7, 48, None), (48, 89, None), (89, 130, None), (130, 207, None)]),
            R(287, 402, 7, 207, "Čitaonica", door=(287, 50)),  # FtnGO "Čitaonica (A0)"
            R(402, 545, 7, 207, "A1", doors=[(470, 7), (545, 188)]),  # Amfiteatar "Nikola Tesla"
            R(545, 693, 7, 168, "A2", doors=[(620, 7), (650, 168)]),  # Amfiteatar "Milutin Milanković"
            R(693, 832, 7, 110, "A3"),
            R(693, 832, 110, 207, "A4", door=(693, 188)),
        ],
        "stairs": [S1, ("S2", (497, -65, 587, -28), (542, -30)), S3],
        "points": [("PROLAZ-F", (40, -13), "PROLAZ"), ("PROLAZ-ITC", (832, -10), "PROLAZ"),
                   ("PROLAZ-NB", (680, 207), "PROLAZ"), ("PROLAZ-KULA", (250, 210), "PROLAZ")],
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
