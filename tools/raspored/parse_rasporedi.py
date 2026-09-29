"""
Pretvara zvanične FTN PDF rasporede nastave u JSON koji aplikacija učitava iz assets-a.

Upotreba (iz korena projekta):
    tools/raspored/.venv/Scripts/python tools/raspored/parse_rasporedi.py rasporedi/*.pdf \
        -o app/src/main/assets/schedule.json

Format PDF-a (FTN, 2026/2027):
  - svaka strana = jedan semestar jednog programa (opciono i modula / stručne oblasti),
  - zaglavlje strane: "РАСПОРЕД ПРЕДАВАЊА - <Основне|Мастер> академске студије  <N> Семестар",
    zatim naziv programa i opciono "Модул: ..." / "Стручна област: ...",
  - tabele po danima (prvi red = naziv dana), kolone: Група-е, Од, До, Учионица, Врста наст.,
    Назив Предмета, Извођач; tabele za blok nastavu imaju i kolonu "Датум" umesto dana,
  - ispod tabela "Напомена:" sa slobodnim tekstom.

Tekst ćelija se NE uzima iz pdfplumber-ovog extract() jer gubi sadržaj ćelija u visokim
(dvoredim) redovima. Umesto toga: granice kolona iz reda zaglavlja, granice reda iz tabele,
pa se tekst čita direktno iz tog pravougaonika na strani.

Sav tekst se preslovljava u latinicu (aplikacija je na latinici).
"""

import argparse
import datetime as dt
import json
import re
import sys
import unicodedata
from pathlib import Path

import pdfplumber

CYR_TO_LAT = {
    "А": "A", "Б": "B", "В": "V", "Г": "G", "Д": "D", "Ђ": "Đ", "Е": "E", "Ж": "Ž", "З": "Z",
    "И": "I", "Ј": "J", "К": "K", "Л": "L", "Љ": "Lj", "М": "M", "Н": "N", "Њ": "Nj", "О": "O",
    "П": "P", "Р": "R", "С": "S", "Т": "T", "Ћ": "Ć", "У": "U", "Ф": "F", "Х": "H", "Ц": "C",
    "Ч": "Č", "Џ": "Dž", "Ш": "Š",
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "ђ": "đ", "е": "e", "ж": "ž", "з": "z",
    "и": "i", "ј": "j", "к": "k", "л": "l", "љ": "lj", "м": "m", "н": "n", "њ": "nj", "о": "o",
    "п": "p", "р": "r", "с": "s", "т": "t", "ћ": "ć", "у": "u", "ф": "f", "х": "h", "ц": "c",
    "ч": "č", "џ": "dž", "ш": "š",
}

DAYS = {"ПОНЕДЕЉАК": 1, "УТОРАК": 2, "СРЕДА": 3, "ЧЕТВРТАК": 4, "ПЕТАК": 5, "СУБОТА": 6, "НЕДЕЉА": 7}

TYPES = {
    "Пред.": "PREDAVANJE",
    "ауд.вежбе": "AUDITORNE_VEZBE",
    "рач.вежбе": "RACUNARSKE_VEZBE",
    "лаб.вежбе": "LABORATORIJSKE_VEZBE",
}

LEVELS = {
    "Основне академске": "OAS", "Основне струковне": "OSS",
    "Мастер академске": "MAS", "Мастер струковне": "MSS",
}
# Naslov je ponekad razmaknut ("Р А С П О Р Е Д ...", i "Н Ј" umesto "Њ") - traži se bez razmaka.
TITLE = "РАСПОРЕДПРЕДАВА"

# Zaglavlje kolone -> ključ
COLUMNS = {
    "Датум": "date", "Група-е": "groups", "Од": "start", "До": "end", "Учионица": "room",
    "Врста наст.": "type", "Назив Предмета": "subject", "Извођач": "lecturers",
}

TIME_RE = re.compile(r"^\d{1,2}:\d{2}$")
DATE_RE = re.compile(r"^(\d{2})\.(\d{2})\.(\d{4})\.?$")


def lat(text):
    """Preslovljava ćirilicu u latinicu; latinični znakovi ostaju (npr. 'Wеб' -> 'Web')."""
    if text is None:
        return None
    # Veliko Љ/Њ/Џ ispred velikog slova (skraćenice, "ЉУБИША") daje "LJ", ne "Lj".
    out = []
    for i, ch in enumerate(text):
        rep = CYR_TO_LAT.get(ch, ch)
        if len(rep) == 2 and ch.isupper():
            nxt = text[i + 1] if i + 1 < len(text) else ""
            if nxt.isupper():
                rep = rep.upper()
        out.append(rep)
    return "".join(out)


def slug(text):
    ascii_ = unicodedata.normalize("NFKD", lat(text).replace("đ", "dj").replace("Đ", "Dj"))
    ascii_ = ascii_.encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", ascii_.lower()).strip("-")


def clean(text):
    """Spaja prelome reda unutar ćelije u razmak."""
    return re.sub(r"\s+", " ", text or "").strip()


def parse_groups(raw):
    """
    'СВИ' -> svi; 'Опредељени' -> samo oni koji su izabrali predmet;
    '1,3' -> grupe 1 i 3; '1(2)' / '1,2 (3,7)' -> smenjuju se svake druge nedelje;
    '1(1-16)' / '1(5-)' -> deo grupe 1 po spisku (opseg studenata, ne grupa);
    '5 св.др.нед.' -> svake druge nedelje; 'ЕП, ММ' / 'ТиПТ' -> stručne oblasti (skraćenica sa
    bar dva velika slova); 'Опред.(1-12)' -> opredeljeni, deo po spisku.
    """
    s = clean(raw)
    biweekly = bool(re.search(r"св\.?\s*др\.?\s*нед|парне\s+нед", s))
    body = re.sub(r"\(?\s*св\.?\s*др\.?\s*нед\.?\s*\)?", " ", s)
    numbers = set()
    for inner in re.findall(r"\(([^()]*)\)", body):
        if "-" not in inner:  # alternacija grupa, npr. 1(2)
            numbers |= {int(n) for n in re.findall(r"\d+", inner)}
            biweekly = True
    outside = re.sub(r"\([^()]*\)", " ", body)
    numbers |= {int(n) for n in re.findall(r"\d+", outside)}
    words = re.findall(r"[А-ЯЂЈЉЊЋЏа-яђјљњћџ]+", outside)
    abbreviations = [w for w in words if sum(c.isupper() for c in w) >= 2]
    return {
        "raw": lat(s),
        "all": "СВИ" in abbreviations,
        "elective": any(w.lower().startswith("опред") for w in words),
        "numbers": sorted(numbers),
        "areas": [lat(w) for w in abbreviations if w != "СВИ"],
        "biweekly": biweekly,
    }


def parse_lecturers(raw):
    # Više izvođača je odvojeno zarezom; prelom reda unutar imena je samo prelom teksta.
    return [clean(p) for p in (raw or "").split(",") if clean(p)]


def cell_text(page, x0, x1, top, bottom):
    region = page.within_bbox((x0 - 0.5, top - 0.5, x1 + 0.5, bottom + 0.5))
    return region.extract_text() or ""


def parse_header(page, first_table_top):
    """Vraća (level, semester, program, module, last_modified) iz teksta iznad prve tabele."""
    text = page.crop((0, 0, page.width, first_table_top)).extract_text() or ""
    lines = [clean(line) for line in text.splitlines() if clean(line)]
    level = semester = program = module = modified = None
    after_title = False
    for line in lines:
        m = re.search(r"Последња измена:\s*([\d.]+\s+[\d:]+)", line)
        if m:
            modified = m.group(1)
        is_title = TITLE in line.replace(" ", "")
        if is_title:
            after_title = True
            level = next((v for k, v in LEVELS.items() if k in line), None)
        if after_title:
            m = re.search(r"(\d+)\s*Семестар", line)
            if m:
                semester = int(m.group(1))
            if is_title or re.fullmatch(r"\d+\s*Семестар", line):
                continue
            line = re.sub(r"\s*\d+\s*Семестар\s*$", "", line)
            if line.startswith(("Модул:", "Стручна област:")):
                module = line.split(":", 1)[1].strip()
            elif program is None:
                program = re.sub(r"^Студијски програм:\s*", "", line)
    return level, semester, program, module, modified


def parse_notes(page, last_table_bottom):
    text = page.crop((0, last_table_bottom, page.width, page.height)).extract_text() or ""
    notes = []
    for line in text.splitlines():
        line = clean(line)
        if not line or line.startswith("Распоред и реализација") or line.rstrip(":") == "Напомена":
            continue
        notes.append(line)
    return notes


def parse_area_groups(notes):
    """
    Napomena sa "група/групе број <brojevi>" i skraćenicom oblasti (bar dva velika slova) ->
    {skraćenica: brojevi}. Oblici sa rasporeda:
      'Стручна област: ... (ЕП) групе број 11,12,13'
      'Групе број 1 су уписане на Топлотну и процесну технику - ТиПТ'
      'Група број 1 је стручна област - Управљачки системи ... (УС)'
      'Група број 11 је група која је уписана на студијску групу Обрада сигнала - ОС'
    """
    result = {}
    for note in notes:
        m = re.search(r"груп\w*\s*број\s*([\d,\sи]+)", note, re.IGNORECASE)
        if not m:
            continue
        numbers = [int(n) for n in re.findall(r"\d+", m.group(1))]
        abbreviations = [w for w in re.findall(r"[А-ЯЂЈЉЊЋЏа-яђјљњћџ]+", note) if sum(c.isupper() for c in w) >= 2]
        if numbers and abbreviations:
            result[lat(abbreviations[-1])] = numbers
    return result


def modified_key(text):
    """'16.09.2026. 06:58' -> (2026, 9, 16, '06:58'), za poređenje verzija; None -> najstarije."""
    m = re.match(r"(\d{2})\.(\d{2})\.(\d{4})\.?\s*([\d:]*)", text or "")
    return (int(m.group(3)), int(m.group(2)), int(m.group(1)), m.group(4)) if m else (0, 0, 0, "")


def parse_table(page, table, state, warnings, where):
    """[state] nosi zaglavlje kolona i trenutni dan na sledeću tabelu iste strane
    (tabele blok nastave imaju zaglavlje samo u prvoj tabeli)."""
    classes = []
    for row in table.rows:
        boxes = [c for c in row.cells if c]
        if not boxes:
            continue
        top = min(b[1] for b in boxes)
        bottom = max(b[3] for b in boxes)
        whole = clean(cell_text(page, table.bbox[0], table.bbox[2], top, bottom))
        compact = whole.replace(" ", "")
        columns = state.get("columns")

        if not whole:
            continue
        if compact in DAYS:
            state["day"] = DAYS[compact]
            continue
        if DATE_RE.match(whole):
            continue  # naslov tabele blok nastave (datum), redovi imaju svoju kolonu datuma
        if "Група-е" in whole and "Од" in whole:
            columns = state["columns"] = []
            layout = state.setdefault("layout", {})
            for cell in row.cells:
                if not cell:
                    continue
                name = clean(cell_text(page, cell[0], cell[2], cell[1], cell[3]))
                if name in COLUMNS:
                    key = COLUMNS[name]
                    layout[(round(cell[0]), round(cell[2]))] = key
                else:
                    # Zaglavlje ponekad nema teksta u ćeliji (Animacija str. 6: "Учионица") -
                    # kolona na istom mestu kao u ranijem zaglavlju istog PDF-a.
                    key = layout.get((round(cell[0]), round(cell[2])))
                if key:
                    columns.append((key, cell[0], cell[2]))
            missing = {"groups", "start", "end", "room", "type", "subject"} - {k for k, _, _ in columns}
            if missing:
                warnings.append(f"{where}: zaglavlje bez kolona {sorted(missing)}")
            continue
        if columns is None:
            warnings.append(f"{where}: red pre zaglavlja tabele: {lat(whole)!r}")
            continue

        values = {key: cell_text(page, x0, x1, top, bottom) for key, x0, x1 in columns}
        start, end = clean(values.get("start")), clean(values.get("end"))
        if not (TIME_RE.match(start) and TIME_RE.match(end)):
            warnings.append(f"{where}: preskočen red bez vremena: {lat(whole)!r}")
            continue

        date = None
        row_day = state.get("day")
        if "date" in values:
            m = DATE_RE.match(clean(values["date"]))
            if m:
                d = dt.date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
                date, row_day = d.isoformat(), d.isoweekday()
        if row_day is None:
            warnings.append(f"{where}: red bez dana: {lat(whole)!r}")
            continue

        type_raw = clean(values.get("type"))
        if type_raw not in TYPES:
            warnings.append(f"{where}: nepoznata vrsta nastave {type_raw!r}")
        groups = parse_groups(values.get("groups"))
        if not (groups["all"] or groups["elective"] or groups["numbers"] or groups["areas"]):
            warnings.append(f"{where}: grupa bez prepoznatog značenja: {groups['raw']!r}")

        classes.append({
            "day": row_day,
            "date": date,
            "start": start.zfill(5),
            "end": end.zfill(5),
            "room": lat(clean(values.get("room"))),
            "type": TYPES.get(type_raw, "OSTALO"),
            "subject": lat(clean(values.get("subject"))),
            "lecturers": [lat(p) for p in parse_lecturers(values.get("lecturers"))],
            "groups": groups,
        })
    return classes


def parse_pdf(path, warnings):
    timetables = {}
    previous_level = None
    layout = {}  # (x0, x1) kolone -> ključ, iz zaglavlja sa tekstom; za zaglavlja bez teksta
    with pdfplumber.open(path) as pdf:
        for index, page in enumerate(pdf.pages, start=1):
            tables = page.find_tables()
            if not tables:
                continue  # uvodna strana ili strana bez tabela (npr. mentorska nastava)
            where = f"{path.name} str. {index}"
            level, semester, program, module, modified = parse_header(page, tables[0].bbox[1])
            # Neki naslovi nemaju nivo studija (EET str. 10, 12) - isti je kao na prethodnoj strani.
            level = previous_level = level or previous_level
            if not (level and semester and program):
                warnings.append(f"{where}: nepotpuno zaglavlje ({level}, {semester}, {program})")
                continue

            classes = []
            state = {"layout": layout}
            for table in tables:
                classes += parse_table(page, table, state, warnings, where)
            notes = parse_notes(page, max(t.bbox[3] for t in tables))

            # Isti program/semestar/modul može da se nastavi na sledećoj strani.
            key = (program, level, semester, module)
            tt = timetables.setdefault(key, {
                "programId": slug(program),
                "program": lat(program),
                "level": level,
                "semester": semester,
                "year": (semester + 1) // 2,
                "module": lat(module),
                "lastModified": modified,
                "source": path.name,
                "notes": [],
                "areaGroups": {},
                "classes": [],
            })
            tt["classes"] += classes
            tt["notes"] += [lat(n) for n in notes if lat(n) not in tt["notes"]]
            tt["areaGroups"].update(parse_area_groups(notes))
            if modified and modified_key(tt["lastModified"]) < modified_key(modified):
                tt["lastModified"] = modified
    return list(timetables.values())


def timetable_key(tt):
    return tt["programId"], tt["level"], tt["semester"], tt["module"] or ""


def keep_newest(timetables, warnings):
    """
    Isti raspored iz dva PDF-a (npr. Primenjeno-softversko-inzenjerstvo-7 i -10 - novija verzija
    istog fajla sa sajta): ostaje onaj sa kasnijom izmenom.
    """
    newest = {}
    for tt in timetables:
        key = timetable_key(tt)
        old = newest.get(key)
        if old is None or modified_key(old["lastModified"]) < modified_key(tt["lastModified"]):
            newest[key] = tt
    for key, tt in newest.items():
        dropped = sorted({t["source"] for t in timetables if timetable_key(t) == key} - {tt["source"]})
        if dropped:
            warnings.append(f"{'|'.join(map(str, key))}: iz {tt['source']}, preskočen {', '.join(dropped)}")
    return [tt for tt in timetables if newest[timetable_key(tt)] is tt]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pdfs", nargs="+", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()

    warnings = []
    timetables = []
    for path in sorted(args.pdfs):
        timetables += parse_pdf(path, warnings)
    timetables = keep_newest(timetables, warnings)
    for tt in timetables:
        tt["classes"].sort(key=lambda c: (c["day"], c["date"] or "", c["start"], c["end"]))

    data = {
        "generatedAt": dt.datetime.now().isoformat(timespec="seconds"),
        "timetables": timetables,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    total = sum(len(t["classes"]) for t in timetables)
    print(f"{len(timetables)} rasporeda, {total} časova -> {args.output}")
    for w in warnings:
        print("UPOZORENJE:", w, file=sys.stderr)


if __name__ == "__main__":
    main()
