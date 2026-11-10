"""
Mašinski institut: geometrija prizemlja i I sprata izmerena zid po zid sa evakuacionih planova (10.11.2026).

Korisnik (10.11.2026): stranica za obeležavanje prostorija MI "treba da 100% prati evakuacione planove". Preklop crteža iz
build_mi.py (03.-10.10.) preko ispravljenih fotografija je pokazao greške do ~1,2 m: granica krila i dugačkog dela je na planu
-730 (bilo -742, po OSM-u), u udubljenjima zida kod krila A i Đ su bile sobe, dogradnja uz D je bila pomerena, pregrade u
redu soba uz krila nisu odgovarale planu. Ovde je sve izmereno ponovo.

Izvor: ispravljene fotografije evakuacionih planova sa stranice "MI i F prostorije" (mi0.jpg, mi1.jpg; box x -170..1050,
y -1160..-470, 1800 px širine = 1,475 px slike po px plana), isečci sa mrežom u px plana (scratchpad sesije 0cec1a97, mi/grid.py).
Koordinate su px plana u zajedničkom sistemu (kao build_mi.py): desno sever, dole istok (glavni ulaz), gore zapad (krila).
Tačnost ~1 px plana (0,1 m).

Za sada je ovo izvor stranice "MI prostorije" (indeksi soba = redosled u ROOMS); build_mi.py (aplikacija) još koristi stari
crtež - prelazi se na ovu geometriju kad korisnik obeleži sobe na stranici.

Soba: (oblik, krilo, vrsta). Oblik je (x0, x1, y0, y1) ili lista tačaka. Krilo: "A", "B", "V", "G", "D", "Đ", kolona hodnika
između krila "AB", "VG", "DĐ", ili None (dugački deo, jezgra, dogradnje). Vrsta: None (soba), "WC" (toalet sa plana).
Hodnici (sivo na planu - evakuacioni putevi) su CORRIDORS; stepeništa STAIRS; I sprat: VOIDS (šrafirano - hale bez poda).
"""


def rect(x0, x1, y0, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


# ---------------------------------------------------------------------------------------------------------------------
# Prizemlje
# ---------------------------------------------------------------------------------------------------------------------

OUTLINE_0 = [
    (-134, -576), (1007.5, -576), (1007.5, -664), (976.5, -664), (976.5, -730), (1012, -730), (1012, -1121),
    (782.5, -1121), (782.5, -730), (714, -730), (714, -713), (620, -713), (620, -730), (552.5, -730), (552.5, -1121),
    (329, -1121), (329, -730), (256, -730), (256, -713), (164, -713), (164, -730), (96, -730), (96, -1121), (-132, -1121),
    (-132, -730), (-99, -730), (-99, -664), (-134, -664),
]
ANNEXES_0 = [
    rect(717.5, 781, -955, -792.5),  # dogradnja uz krilo D: učionica
    rect(735, 772.5, -792.5, -763),  # magacin ispod nje
    rect(96, 139, -925, -894),  # uz krilo B: magacin
    rect(97.5, 121, -894, -859),  # kompresorska stanica
]

BAR_SOUTH_0 = [-100, -69, -36.5, -2.5, 62.5, 95, 127.5, 192.5, 225, 257, 290.75, 357, 388.75]
BAR_SOUTH_0_EAST = [453.75, 518.75, 552, 585, 618.75, 651.25, 681, 714, 747.5, 780, 845, 879, 910, 943.75, 975, 1007.5]
S0 = (-631.5, -576)  # južni red
N0 = (-730, -659)  # red soba uz krila

ROOMS_0 = (
    # dugački deo, zapadni kraj: predsoblje (hodnik se završava na -81) i soba ispod njega
    [([(-134, -659), (-81, -659), (-81, -631.5), (-100, -631.5), (-100, -623), (-134, -623)], None, None),
     (rect(-134, -100, -623, -576), None, None)]
    + [(rect(a, b, *S0), None, None) for a, b in zip(BAR_SOUTH_0, BAR_SOUTH_0[1:])]
    + [(rect(421, 452.5, -604, -576), None, None)]  # portirnica
    + [(rect(a, b, *S0), None, None) for a, b in zip(BAR_SOUTH_0_EAST, BAR_SOUTH_0_EAST[1:]) if a != 879]
    + [(rect(879, 910, -615, -576), None, None)]  # ispod malog hola (879-910, -631..-615)
    # red uz krila
    + [(rect(-99, -57, -730, -693), None, None), (rect(-57, -35, -730, -705), None, "WC"), (rect(-99, -35, -693, -659), None, None),
       (rect(-1, 62.5, *N0), None, None), (rect(62.5, 112.5, *N0), None, None), (rect(112.5, 163, *N0), None, None),
       (rect(164, 194, -713, -659), None, "WC"), (rect(226, 256, -713, -659), None, None),
       (rect(257.5, 356, *N0), None, None), (rect(357.5, 379.5, -730, -689), None, None), (rect(379.5, 422.5, -730, -689), None, "WC"),
       (rect(357.5, 422.5, -689, -659), None, None),
       (rect(454.5, 519, -730, -691), None, None), (rect(454.5, 519, -691, -659), None, None),  # trafostanica / GRO, soba ispod
       (rect(520, 619, *N0), None, None), (rect(620, 651, -713, -659), None, None), (rect(682.5, 714, -713, -659), None, None),
       (rect(714, 814, *N0), None, None), (rect(815, 879, -730, -688), None, "WC"), (rect(815, 879, -688, -659), None, None),
       (rect(911, 976, *N0), None, None)]
    # krilo A
    + [(rect(-132, -34, -1121, -1087.5), "A", None), (rect(-132, -84, -1087.5, -990), "A", None), (rect(-84, -34, -1087.5, -990), "A", None),
       (rect(-132, -34, -990, -827.5), "A", None),  # magacin
       (rect(-132, -34, -827.5, -795), "A", None),  # tehnički pregled
       (rect(-132, -100, -795, -730), "A", None), (rect(-100, -34, -795, -730), "A", None)]
    # kolona A|B iznad hodnika
    + [(rect(-33, -1, -1121, -1087), "AB", None), (rect(-33, -1, -1087, -1031), "AB", None), (rect(-33, -1, -1031, -964), "AB", None),
       (rect(-33, -1, -964, -937.5), "AB", None)]
    # krilo B
    + [(rect(-1, 96, -1121, -1023), "B", None), (rect(-1, 96, -1023, -990), "B", None), (rect(-1, 96, -990, -937.5), "B", None),
       (rect(-1, 96, -925, -892.5), "B", None), (rect(-1, 96, -892.5, -777.5), "B", None), (rect(-1, 96, -777.5, -730), "B", None),
       (rect(96, 139, -925, -894), "B", None), (rect(97.5, 121, -891, -859), "B", None)]  # magacin, kompresorska stanica
    # krilo V
    + [(rect(329, 425, -1121, -957.5), "V", None), (rect(329, 375, -957.5, -936), "V", None), (rect(329, 375, -936, -860), "V", None),
       (rect(375, 425, -924, -860), "V", None), (rect(329, 425, -860, -796), "V", None),
       ([(329, -796), (400, -796), (400, -762), (386, -762), (386, -730), (329, -730)], "V", None), (rect(400, 425, -796, -762), "V", None)]
    # kolona V|G iznad hodnika
    + [(rect(424, 453, -1121, -1046), "VG", None), (rect(424, 453, -1046, -992.5), "VG", None)]
    # krilo G
    + [([(456, -1121), (552.5, -1121), (552.5, -1021), (492.5, -1021), (492.5, -955), (456, -955)], "G", None),  # hala
       (rect(492.5, 552.5, -1021, -955), "G", None), (rect(507, 552.5, -955, -924), "G", None), (rect(503, 552.5, -924, -860), "G", None),
       (rect(456, 552.5, -860, -730), "G", None)]
    # kolona D|Đ iznad hodnika
    + [(rect(883, 913, -1121, -1086), "DĐ", None), (rect(883, 913, -1086, -1054), "DĐ", None)]
    # krilo D
    + [([(782.5, -1121), (825, -1121), (825, -1086), (817.5, -1086), (817.5, -1054), (782.5, -1054)], "D", None),
       (rect(826, 881, -1121, -1086), "D", None), (rect(818, 881, -1086, -1054), "D", None),
       (rect(782.5, 881, -1054, -924), "D", None), (rect(782.5, 881, -924, -826), "D", None),
       (rect(782.5, 881, -826, -795), "D", None),  # učionica
       (rect(782.5, 881, -795, -730), "D", None)]
    # dogradnja uz D (učionica, magacin)
    + [(rect(717.5, 781, -955, -792.5), "D", None), (rect(735, 772.5, -792.5, -763), "D", None)]
    # krilo Đ
    + [(rect(914, 1012, -1121, -1054), "Đ", None), (rect(914, 1012, -1054, -989), "Đ", None), (rect(914, 1012, -989, -892.5), "Đ", None),
       (rect(914, 1012, -892.5, -859), "Đ", None), (rect(914, 1012, -859, -794), "Đ", None),
       ([(914, -794), (1012, -794), (1012, -730), (961, -730), (961, -764), (914, -764)], "Đ", None), (rect(914, 961, -764, -730), "Đ", None)]
)

CORRIDORS_0 = [
    rect(-81, 910, -659, -631.5),  # hodnik dugačkog dela
    rect(911, 1007.5, -659, -631.5), rect(879, 910, -631.5, -615),  # istočni hol i mali hol južnog reda
    [(388.75, -631.5), (452.5, -631.5), (452.5, -604), (421, -604), (421, -576), (388.75, -576)],  # predvorje glavnog ulaza
    rect(-57, -35, -705, -693),  # predsoblje toaleta uz kolonu A|B
    rect(-33, -1, -937.5, -659),  # hodnik A|B
    rect(-1, 96, -937.5, -925),  # prolaz do istočnog izlaza krila B
    rect(424, 453, -992.5, -659),  # hodnik V|G
    rect(375, 425, -957.5, -924),  # predvorje stepeništa SV
    rect(386, 425, -762, -730),  # predvorje stepeništa SV2
    rect(457, 503, -955, -860),  # predvorje stepeništa SG
    rect(883, 913, -1054, -659),  # hodnik D|Đ
]
STAIRS_0 = [
    (195, -722.5, 226, -660),  # S1 (jezgro A/B - V/G)
    (652, -722.5, 681, -660),  # S2 (jezgro V/G - D/Đ)
    (-69, -796, -35, -779),  # SAB
    (388, -956, 410, -945),  # SV
    (386, -762, 415, -732),  # SV2
    (469, -945, 504, -901),  # SG
    (855, -1054, 879, -1012),  # SD1
    (829, -922, 861, -906),  # SD2
    (836, -825, 879, -811),  # SD3
    (914, -989, 954, -973),  # SĐ
]
ENTRANCE_0 = (404.5, -576)  # glavni ulaz (dvokrilna vrata u južnom zidu; na planu nema predvorja koje OSM crta do -507)

# ---------------------------------------------------------------------------------------------------------------------
# I sprat (fotografija plana visi okrenuta za 180° - ovde okrenuta kao prizemlje). Zidovi krila se od prizemlja razlikuju za
# par px (druga fotografija) - mereno posebno.
# ---------------------------------------------------------------------------------------------------------------------

OUTLINE_1 = [
    (-134, -577.5), (1008.75, -577.5), (1008.75, -664), (977.5, -664), (977.5, -730), (1012.5, -730), (1012.5, -1121),
    (782.5, -1121), (782.5, -730), (715, -730), (715, -714), (619, -714), (619, -730), (552.5, -730), (552.5, -1121),
    (326, -1121), (326, -730), (257.5, -730), (257.5, -714), (163, -714), (163, -730), (96, -730), (96, -1121), (-131, -1121),
    (-131, -730), (-98.75, -730), (-98.75, -664), (-134, -664),
]
ANNEXES_1 = []

BAR_SOUTH_1 = [-33.75, 0, 31.25, 63.75, 96.25, 130, 162.5, 195, 226.25, 323.75, 357.5, 388.75, 422.5, 452.5, 485, 517.5, 551.25,
               583.75, 617.5, 650, 682.5, 715, 747.5, 780, 812.5, 845, 878.75, 943.75, 976.25, 1008.75]
S1_ = (-632.5, -577.5)

ROOMS_1 = (
    # dugački deo, zapadni kraj: dve sobe, hol sa četvoro vrata, soba ispod njega
    [(rect(-134, -66.25, -658.5, -632.5), None, None), (rect(-134, -66.25, -632.5, -577.5), None, None),
     (rect(-66.25, -33.75, -658.5, -621), None, None), (rect(-66.25, -33.75, -621, -577.5), None, None)]
    + [(rect(a, b, *S1_), None, None) for a, b in zip(BAR_SOUTH_1, BAR_SOUTH_1[1:])]
    + [(rect(943.75, 1008.75, -658.5, -632.5), None, None)]  # istočni hol (vrata sa kraja hodnika)
    # red uz krila
    + [(rect(-98.75, -34, -730, -694.5), None, None), (rect(-98.75, -51, -694.5, -660), None, None),
       (rect(-32.5, -1, -730, -660), "AB", None),  # kolona A|B ispod krila (razvodni ormar)
       (rect(1, 64, -730, -694.5), None, None), (rect(16, 64, -694.5, -660), None, None), (rect(65, 81, -730, -660), None, None),
       (rect(82.5, 109, -730, -694.5), None, None), (rect(82.5, 119, -694.5, -660), None, None), (rect(109, 130, -730, -694.5), None, None),
       (rect(130, 163, -730, -660), None, None), (rect(163, 195, -714, -660), None, "WC"), (rect(227.5, 257.5, -714, -660), None, None),
       (rect(258.75, 291.25, -730, -660), None, None), (rect(291.25, 323.75, -730, -676), None, None),
       (rect(324, 340, -730, -660), None, None), (rect(342, 356.5, -730, -660), None, None),
       (rect(358, 420, -730, -694), None, None), (rect(358, 403, -694, -660), None, None),
       (rect(422, 452, -730, -676), "VG", None),
       (rect(454, 518, -730, -695), None, None), (rect(471, 518, -695, -660), None, None), (rect(521, 535, -730, -660), None, None),
       (rect(538, 585, -730, -700), None, None), (rect(538, 573, -688, -660), None, None), (rect(587, 619, -730, -660), None, None),
       (rect(619, 650, -714, -660), None, None), (rect(682.5, 715, -714, -660), None, "WC"),
       (rect(716, 747, -730, -659), None, None), (rect(749, 780, -730, -659), None, None), (rect(782, 796, -730, -659), None, None),
       (rect(799, 812, -730, -659), None, None),
       ([(815, -730), (879, -730), (879, -717), (900, -717), (900, -675), (879, -675), (862, -659.5), (815, -659.5)], None, None),
       (rect(911, 977.5, -730, -659), None, None)]
    # krilo A
    + [(rect(-131, -31, -1121, -1040), "A", None), (rect(-131, -31, -1040, -990), "A", None), (rect(-131, -31, -990, -894), "A", None),
       (rect(-131, -31, -894, -860), "A", None), (rect(-131, -31, -860, -827.5), "A", None),
       (rect(-131, -67.5, -796, -731), "A", None), (rect(-66, -31, -779, -731), "A", None)]
    # kolona A|B: soba na kraju hodnika
    + [(rect(-30, 0, -1121, -1074), "AB", None)]
    # krilo B
    + [(rect(1, 96, -1121, -1087.5), "B", None), (rect(17.5, 96, -1086, -1055), "B", None), (rect(1, 96, -1055, -956), "B", None),
       (rect(1, 36, -945, -924), "B", None), (rect(56, 96, -956, -924), "B", None), (rect(1, 96, -924, -860), "B", None),
       (rect(1, 96, -860, -827.5), "B", None), (rect(1, 96, -827.5, -795), "B", None), (rect(1, 96, -795, -762.5), "B", None),
       (rect(1, 96, -762.5, -730), "B", None)]
    # krilo V (gornji i srednji deo su hale bez poda)
    + [(rect(326, 368, -956, -860), "V", None), (rect(369, 424, -924, -860), "V", None),
       (rect(325, 386, -794, -731), "V", None), (rect(386, 424, -794, -762.5), "V", None)]
    # krilo G
    + [(rect(457.5, 552.5, -1121, -1054), "G", None), (rect(475, 552.5, -1052.5, -1021), "G", None), (rect(475, 552.5, -1021, -956), "G", None),
       (rect(500, 552.5, -956, -925), "G", None), (rect(500, 552.5, -925, -902), "G", None), (rect(457.5, 552.5, -890, -827.5), "G", None)]
    # kolona D|Đ: soba na kraju hodnika
    + [(rect(884, 914, -1121, -1087.5), "DĐ", None)]
    # krilo D
    + [(rect(784, 852.5, -1121, -1055), "D", None), (rect(854, 881, -1121, -1055), "D", None),
       (rect(784, 827.5, -924, -891), "D", None), (rect(860, 881, -924, -891), "D", None), (rect(783, 881, -891, -826), "D", None),
       (rect(784, 819, -826, -805), "D", None), (rect(836, 881, -812.5, -792.5), "D", None),
       ([(783, -805), (820, -805), (820, -799), (836, -799), (836, -792.5), (881, -792.5), (881, -760), (783, -760)], "D", None)]
    # krilo Đ
    + [(rect(915, 1012, -1121, -1055), "Đ", None), (rect(915, 951, -1052.5, -990), "Đ", None), (rect(915, 951, -975, -956), "Đ", None),
       (rect(915, 951, -956, -925), "Đ", None), (rect(969, 1012, -1052.5, -989), "Đ", None), (rect(969, 1012, -989, -925), "Đ", None),
       (rect(913, 1010, -924, -859), "Đ", None),
       ([(913, -791), (1010, -791), (1010, -731), (958.75, -731), (958.75, -764), (913, -764)], "Đ", None)]
)

CORRIDORS_1 = [
    rect(-33.75, 943, -658.5, -632.5),  # hodnik dugačkog dela
    rect(-50, -34, -694.5, -660), rect(1, 15, -694.5, -660), rect(119, 130, -694.5, -660),  # mali holovi reda uz krila
    rect(291.25, 323.75, -676, -660), rect(404, 419, -694, -661), rect(422, 452, -676, -661), rect(454, 470, -694, -661),
    [(538, -700), (585, -700), (585, -660), (573, -660), (573, -688), (538, -688)],
    [(862, -659.5), (879, -675), (900, -675), (900, -659.5)],
    rect(-31, -1, -1074, -730),  # hodnik A|B (sa stepenicama u hodniku)
    rect(1, 16, -1086, -1056),  # predvorje krila B na kraju
    [(1, -956), (56, -956), (56, -924), (36, -924), (36, -945), (1, -945)],  # predvorje krila B kod sredine
    rect(368, 425, -956, -924),  # krilo V: predvorje stepeništa SV
    rect(457.5, 474, -1054, -956),  # krilo G: hodnik uz levi zid
    rect(457.5, 500, -956, -902), rect(457.5, 552.5, -902, -890),  # krilo G: predvorje stepeništa SG i prolaz
    rect(783, 855, -1055, -1042.5), rect(830, 845, -907.5, -891), rect(820, 836, -825, -799),  # krilo D: holovi stepeništa
    rect(952.5, 969, -1054, -925),  # krilo Đ: hodnik po sredini
    rect(881, 911, -1087.5, -717), rect(900, 910, -717, -659.5),  # hodnik D|Đ
]
STAIRS_1 = [
    (196, -714, 226, -660),  # S1
    (651, -714, 681, -660),  # S2
    (-65, -796, -34, -779),  # SAB
    (-31, -827.5, -16, -762.5),  # stepenice u hodniku A|B (samo crtež)
    (375, -956, 405, -935),  # SV
    (386, -762.5, 417.5, -731),  # SV2
    (467.5, -944, 500, -911),  # SG
    (855, -1054, 879, -1019),  # SD1
    (827.5, -924, 860, -907.5),  # SD2
    (836, -826, 865, -812.5),  # SD3
    (915, -990, 951, -975),  # SĐ
]
VOIDS_1 = [  # šrafirano na planu: hale bez poda na spratu, spoljne stepenice krila A, okno u Đ; kolona V|G nema pod
    (-131, -827.5, -31, -797.5), (326, -1121, 425, -956), (325, -860, 424, -795), (426, -1109, 452.5, -730),
    (456, -827.5, 552.5, -731), (783, -1042.5, 881, -924), (783, -760, 881, -731), (913, -859, 1010, -792.5),
    (913.75, -764, 958.75, -731),
]
