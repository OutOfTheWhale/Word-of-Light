#!/usr/bin/env python3
"""Download the King James Version and emit Word of Light modules.

The KJV (1611) is public domain, so unlike every other translation in the app
it can ship inside the APK and read with no network, no key and no terms. That
is why it is the default and the anchor for the word-study features.

Source repository stores one file per book as:

    {"book": "Genesis",
     "chapters": [{"chapter": "1",
                   "verses": [{"verse": "1", "text": "..."}]}]}

Output matches what bible_import.py produces, so bundled and imported books are
the same shape and ModuleStore cannot tell them apart:

    {"book": "Genesis", "translation": "KJV",
     "verses": [{"chapter": 1, "verse": 1, "heading": null,
                 "lines": [{"text": "...", "poetry": false}]}]}

This source carries no section headings and no poetry marking, so those come
through null/false. A Strong's-tagged edition can layer on later without the
module format changing.

Usage:
    python fetch_kjv.py --out ../../tool/src/main/assets/bible/kjv
"""

import argparse
import json
import os
import sys
import urllib.request

SOURCE = "https://raw.githubusercontent.com/aruljohn/Bible-kjv/master/{}.json"

# Source filename -> (Word of Light book id, display name).
# Order matches the canon, so progress reads sensibly.
BOOKS = [
    ("Genesis", "gen", "Genesis"),
    ("Exodus", "exo", "Exodus"),
    ("Leviticus", "lev", "Leviticus"),
    ("Numbers", "num", "Numbers"),
    ("Deuteronomy", "deu", "Deuteronomy"),
    ("Joshua", "jos", "Joshua"),
    ("Judges", "jdg", "Judges"),
    ("Ruth", "rut", "Ruth"),
    ("1Samuel", "1sa", "1 Samuel"),
    ("2Samuel", "2sa", "2 Samuel"),
    ("1Kings", "1ki", "1 Kings"),
    ("2Kings", "2ki", "2 Kings"),
    ("1Chronicles", "1ch", "1 Chronicles"),
    ("2Chronicles", "2ch", "2 Chronicles"),
    ("Ezra", "ezr", "Ezra"),
    ("Nehemiah", "neh", "Nehemiah"),
    ("Esther", "est", "Esther"),
    ("Job", "job", "Job"),
    ("Psalms", "psa", "Psalms"),
    ("Proverbs", "pro", "Proverbs"),
    ("Ecclesiastes", "ecc", "Ecclesiastes"),
    ("SongofSolomon", "sng", "Song of Songs"),
    ("Isaiah", "isa", "Isaiah"),
    ("Jeremiah", "jer", "Jeremiah"),
    ("Lamentations", "lam", "Lamentations"),
    ("Ezekiel", "ezk", "Ezekiel"),
    ("Daniel", "dan", "Daniel"),
    ("Hosea", "hos", "Hosea"),
    ("Joel", "jol", "Joel"),
    ("Amos", "amo", "Amos"),
    ("Obadiah", "oba", "Obadiah"),
    ("Jonah", "jon", "Jonah"),
    ("Micah", "mic", "Micah"),
    ("Nahum", "nam", "Nahum"),
    ("Habakkuk", "hab", "Habakkuk"),
    ("Zephaniah", "zep", "Zephaniah"),
    ("Haggai", "hag", "Haggai"),
    ("Zechariah", "zec", "Zechariah"),
    ("Malachi", "mal", "Malachi"),
    ("Matthew", "mat", "Matthew"),
    ("Mark", "mrk", "Mark"),
    ("Luke", "luk", "Luke"),
    ("John", "jhn", "John"),
    ("Acts", "act", "Acts"),
    ("Romans", "rom", "Romans"),
    ("1Corinthians", "1co", "1 Corinthians"),
    ("2Corinthians", "2co", "2 Corinthians"),
    ("Galatians", "gal", "Galatians"),
    ("Ephesians", "eph", "Ephesians"),
    ("Philippians", "php", "Philippians"),
    ("Colossians", "col", "Colossians"),
    ("1Thessalonians", "1th", "1 Thessalonians"),
    ("2Thessalonians", "2th", "2 Thessalonians"),
    ("1Timothy", "1ti", "1 Timothy"),
    ("2Timothy", "2ti", "2 Timothy"),
    ("Titus", "tit", "Titus"),
    ("Philemon", "phm", "Philemon"),
    ("Hebrews", "heb", "Hebrews"),
    ("James", "jas", "James"),
    ("1Peter", "1pe", "1 Peter"),
    ("2Peter", "2pe", "2 Peter"),
    ("1John", "1jn", "1 John"),
    ("2John", "2jn", "2 John"),
    ("3John", "3jn", "3 John"),
    ("Jude", "jud", "Jude"),
    ("Revelation", "rev", "Revelation"),
]

# Chapter counts, checked per book. A truncated download is otherwise silent -
# you would only find the missing chapters by scrolling into them on the phone.
CHAPTERS = {
    "gen": 50, "exo": 40, "lev": 27, "num": 36, "deu": 34, "jos": 24, "jdg": 21,
    "rut": 4, "1sa": 31, "2sa": 24, "1ki": 22, "2ki": 25, "1ch": 29, "2ch": 36,
    "ezr": 10, "neh": 13, "est": 10, "job": 42, "psa": 150, "pro": 31,
    "ecc": 12, "sng": 8, "isa": 66, "jer": 52, "lam": 5, "ezk": 48, "dan": 12,
    "hos": 14, "jol": 3, "amo": 9, "oba": 1, "jon": 4, "mic": 7, "nam": 3,
    "hab": 3, "zep": 3, "hag": 2, "zec": 14, "mal": 4, "mat": 28, "mrk": 16,
    "luk": 24, "jhn": 21, "act": 28, "rom": 16, "1co": 16, "2co": 13, "gal": 6,
    "eph": 6, "php": 4, "col": 4, "1th": 5, "2th": 3, "1ti": 6, "2ti": 4,
    "tit": 3, "phm": 1, "heb": 13, "jas": 5, "1pe": 5, "2pe": 3, "1jn": 5,
    "2jn": 1, "3jn": 1, "jud": 1, "rev": 22,
}

# The KJV has 31,102 verses. A well-known total, and a cheap end-to-end check
# that no book came back short.
TOTAL_VERSES = 31102


def fetch(name, timeout=60):
    with urllib.request.urlopen(SOURCE.format(name), timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def convert(raw, display_name):
    verses = []
    for chapter in raw.get("chapters", []):
        number = int(chapter["chapter"])
        for verse in chapter.get("verses", []):
            text = " ".join(verse["text"].split())
            verses.append({
                "chapter": number,
                "verse": int(verse["verse"]),
                "heading": None,
                "lines": [{"text": text, "poetry": False}],
            })
    return {"book": display_name, "translation": "KJV", "verses": verses}


def check(book_id, module):
    problems = []
    chapters = {v["chapter"] for v in module["verses"]}
    expected = CHAPTERS[book_id]
    if chapters != set(range(1, expected + 1)):
        missing = sorted(set(range(1, expected + 1)) - chapters)
        problems.append("%s: missing chapters %s" % (book_id, missing[:10]))
    for chapter in sorted(chapters):
        numbers = sorted(v["verse"] for v in module["verses"] if v["chapter"] == chapter)
        if numbers != list(range(1, len(numbers) + 1)):
            problems.append("%s %d: verses not contiguous" % (book_id, chapter))
    if any(not v["lines"][0]["text"] for v in module["verses"]):
        problems.append("%s: at least one empty verse" % book_id)
    return problems


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="assets/bible/kjv directory")
    ap.add_argument("--only", help="single book id, for a quick test")
    args = ap.parse_args(argv)

    os.makedirs(args.out, exist_ok=True)
    wanted = [b for b in BOOKS if not args.only or b[1] == args.only]

    total = 0
    problems = []
    for index, (source_name, book_id, display_name) in enumerate(wanted, 1):
        try:
            module = convert(fetch(source_name), display_name)
        except Exception as exc:
            problems.append("%s: download failed (%s)" % (book_id, exc))
            continue

        problems.extend(check(book_id, module))
        path = os.path.join(args.out, "%s.json" % book_id)
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(module, fh, ensure_ascii=False, separators=(",", ":"))

        total += len(module["verses"])
        print("  [%2d/%d] %-16s %5d verses" % (index, len(wanted), display_name,
                                               len(module["verses"])), file=sys.stderr)

    print("\n%d books, %d verses" % (len(wanted) - len(
        [p for p in problems if "download failed" in p]), total), file=sys.stderr)

    if not args.only:
        if total == TOTAL_VERSES:
            print("verse total matches the KJV's %d" % TOTAL_VERSES, file=sys.stderr)
        else:
            problems.append("verse total is %d, expected %d" % (total, TOTAL_VERSES))

    for p in problems:
        print("  WARNING: %s" % p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
