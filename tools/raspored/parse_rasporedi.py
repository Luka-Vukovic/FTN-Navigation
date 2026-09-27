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

LEVELS = {"Основне": "OAS", "Мастер": "MAS"}

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
    '5 св.др.нед.' -> svake druge nedelje; 'ЕП, ММ' -> stručne oblasti (mastar).
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
    upper_words = [w for w in words if w.isupper()]
    return {
        "raw": lat(s),
        "all": "СВИ" in upper_words,
        "elective": any(w.lower().startswith("опредељен") for w in words),
        "numbers": sorted(numbers),
        "areas": [lat(w) for w in upper_words if w != "СВИ"],
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
        if "РАСПОРЕД ПРЕДАВАЊА" in line:
            after_title = True
            level = next((v for k, v in LEVELS.items() if k in line), None)
        if after_title:
            m = re.search(r"(\d+)\s*Семестар", line)
            if m:
                semester = int(m.group(1))
            if "РАСПОРЕД ПРЕДАВАЊА" in line or re.fullmatch(r"\d+\s*Семестар", line):
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
    """'Стручна област: ... (ЕП) групе број 15' -> {'EP': [15]}"""
    result = {}
    for note in notes:
        m = re.search(r"\(([А-ЯЂЈЉЊЋЏ]+)\)\s*груп\w*\s*број\s*([\d,\s]+)", note)
        if m:
            result[lat(m.group(1))] = [int(n) for n in re.findall(r"\d+", m.group(2))]
    return result


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
            for cell in row.cells:
                if not cell:
                    continue
                name = clean(cell_text(page, cell[0], cell[2], cell[1], cell[3]))
                if name in COLUMNS:
                    columns.append((COLUMNS[name], cell[0], cell[2]))
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
    with pdfplumber.open(path) as pdf:
        for index, page in enumerate(pdf.pages, start=1):
            tables = page.find_tables()
            if not tables:
                continue  # uvodna strana ili strana bez tabela (npr. mentorska nastava)
            where = f"{path.name} str. {index}"
            level, semester, program, module, modified = parse_header(page, tables[0].bbox[1])
            if not (level and semester and program):
                warnings.append(f"{where}: nepotpuno zaglavlje ({level}, {semester}, {program})")
                continue

            classes = []
            state = {}
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
            if modified and (tt["lastModified"] or "") < modified:
                tt["lastModified"] = modified
    return list(timetables.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pdfs", nargs="+", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()

    warnings = []
    timetables = []
    for path in sorted(args.pdfs):
        timetables += parse_pdf(path, warnings)
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
