#!/usr/bin/env python3
"""Download the Strong's-tagged KJV and its lexicon, and emit app assets.

Replaces the flat KJV from fetch_kjv.py. Both the 1611 KJV and Strong's 1890
concordance are public domain, so this is the one translation that can ship
inside the APK and also carry word-study data.

Source verse text looks like:

    Jude,[G2455] the servant[G1401] ... <em>be</em> glory[G1391]

    [G####] / [H####]  Strong's number for the preceding word
    <em>...</em>       words the KJV translators supplied, printed in italics

Two things this source does NOT have, and no public-domain KJV does:

  * section headings - modern ones are editorial and usually copyrighted
  * poetry line structure - so Psalms and Job read as prose

The module format supports both, so a better source can fill them in later
without anything downstream changing.

Output:
  assets/bible/kjv/<book>.json    verse text, tags preserved inline
  assets/lexicon/<bucket>.json    Strong's entries, bucketed by hundred

The lexicon is bucketed because it is ~6 MB across 12,040 entries. Parsing all
of that to look up one tapped word would be painfully slow on the phone; a
bucket is ~50 KB.

Usage:
    python fetch_kjv_tagged.py --assets ../../tool/src/main/assets
"""

import argparse
import html
import json
import os
import re
import sys
import time
import urllib.request

BOOKS_URL = "https://raw.githubusercontent.com/kaiserlik/kjv/main/{}.json"
LEXICON_URL = "https://raw.githubusercontent.com/kaiserlik/kjv/main/lexicon.json"

# Source file stem -> (app book id, display name). Source uses its own
# abbreviations: Rth not Rut, Jde not Jud, Mar not Mrk, Phl not Php, 1Jo not 1Jn.
BOOKS = [
    ("Gen", "gen", "Genesis"), ("Exo", "exo", "Exodus"),
    ("Lev", "lev", "Leviticus"), ("Num", "num", "Numbers"),
    ("Deu", "deu", "Deuteronomy"), ("Jos", "jos", "Joshua"),
    ("Jdg", "jdg", "Judges"), ("Rth", "rut", "Ruth"),
    ("1Sa", "1sa", "1 Samuel"), ("2Sa", "2sa", "2 Samuel"),
    ("1Ki", "1ki", "1 Kings"), ("2Ki", "2ki", "2 Kings"),
    ("1Ch", "1ch", "1 Chronicles"), ("2Ch", "2ch", "2 Chronicles"),
    ("Ezr", "ezr", "Ezra"), ("Neh", "neh", "Nehemiah"),
    ("Est", "est", "Esther"), ("Job", "job", "Job"),
    ("Psa", "psa", "Psalms"), ("Pro", "pro", "Proverbs"),
    ("Ecc", "ecc", "Ecclesiastes"), ("Sng", "sng", "Song of Songs"),
    ("Isa", "isa", "Isaiah"), ("Jer", "jer", "Jeremiah"),
    ("Lam", "lam", "Lamentations"), ("Eze", "ezk", "Ezekiel"),
    ("Dan", "dan", "Daniel"), ("Hos", "hos", "Hosea"),
    ("Joe", "jol", "Joel"), ("Amo", "amo", "Amos"),
    ("Oba", "oba", "Obadiah"), ("Jon", "jon", "Jonah"),
    ("Mic", "mic", "Micah"), ("Nah", "nam", "Nahum"),
    ("Hab", "hab", "Habakkuk"), ("Zep", "zep", "Zephaniah"),
    ("Hag", "hag", "Haggai"), ("Zec", "zec", "Zechariah"),
    ("Mal", "mal", "Malachi"), ("Mat", "mat", "Matthew"),
    ("Mar", "mrk", "Mark"), ("Luk", "luk", "Luke"),
    ("Jhn", "jhn", "John"), ("Act", "act", "Acts"),
    ("Rom", "rom", "Romans"), ("1Co", "1co", "1 Corinthians"),
    ("2Co", "2co", "2 Corinthians"), ("Gal", "gal", "Galatians"),
    ("Eph", "eph", "Ephesians"), ("Phl", "php", "Philippians"),
    ("Col", "col", "Colossians"), ("1Th", "1th", "1 Thessalonians"),
    ("2Th", "2th", "2 Thessalonians"), ("1Ti", "1ti", "1 Timothy"),
    ("2Ti", "2ti", "2 Timothy"), ("Tit", "tit", "Titus"),
    ("Phm", "phm", "Philemon"), ("Heb", "heb", "Hebrews"),
    ("Jas", "jas", "James"), ("1Pe", "1pe", "1 Peter"),
    ("2Pe", "2pe", "2 Peter"), ("1Jo", "1jn", "1 John"),
    ("2Jo", "2jn", "2 John"), ("3Jo", "3jn", "3 John"),
    ("Jde", "jud", "Jude"), ("Rev", "rev", "Revelation"),
]

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

TOTAL_VERSES = 31102
BUCKET_SIZE = 100

# Old Testament books must never carry Greek tags, and vice versa. This is the
# check that would have caught the Hebrew-in-the-New-Testament bug.
OT_IDS = set(list(CHAPTERS)[:39])

_TAG = re.compile(r"\[([GH])(\d+)\]")
_EM = re.compile(r"</?em>")


# Some source files contain \\" where \" was meant. JSON reads that as an
# escaped backslash followed by a closing quote, so the string ends early.
_BAD_ESCAPE = re.compile(r'\\\\"')

# Last-resort reader: pull the English field straight out, ignoring the rest of
# the record. Matches  "1Co|1|1":{"en":"...."  with JSON string escaping.
_EN_FIELD = re.compile(
    r'"[A-Za-z0-9]+\|(\d+)\|(\d+)"\s*:\s*\{\s*"en"\s*:\s*"((?:[^"\\]|\\.)*)"'
)


def download(url, timeout=120, attempts=3):
    """Bytes off the network. Retries only genuine connection failures.

    Malformed JSON is not transient - it fails at the same byte every time -
    so it is handled by load_book instead of being retried.
    """
    last = None
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=timeout) as response:
                return response.read().decode("utf-8")
        except OSError as exc:
            last = exc
            if attempt < attempts - 1:
                time.sleep(2 ** attempt)
    raise last


def verses_from_structure(raw):
    """Walk book -> chapter -> verse, keyed off each verse's own id.

    The outer grouping cannot be trusted. In some books each chapter entry is
    cumulative - '1Th|5' holds all 89 verses of the whole book - while others,
    Genesis among them, are clean. Placing each verse by its own key makes the
    grouping irrelevant either way.
    """
    found = {}
    conflicts = []
    book = next(iter(raw.values()))
    for chapter in book.values():
        for verse_key, verse in chapter.items():
            parts = verse_key.split("|")
            if len(parts) != 3:
                continue
            try:
                ref = (int(parts[1]), int(parts[2]))
            except ValueError:
                continue
            tagged = " ".join(verse["en"].split())
            record(found, conflicts, ref, tagged)
    return found, conflicts


def verses_from_english_only(body):
    """Recover verses from a document that will not parse.

    Every corruption found in this source sits in the Bulgarian, Chinese or
    Spanish fields - unescaped quotes inside them end the string early. Those
    fields are never read here, so scanning out the English directly sidesteps
    the problem rather than trying to repair text we do not want.
    """
    found = {}
    conflicts = []
    for match in _EN_FIELD.finditer(body):
        ref = (int(match.group(1)), int(match.group(2)))
        try:
            text = json.loads('"%s"' % match.group(3))
        except json.JSONDecodeError:
            continue
        record(found, conflicts, ref, " ".join(text.split()))
    return found, conflicts


def record(found, conflicts, ref, tagged):
    """Keep the first reading; flag a repeat that disagrees rather than guess."""
    previous = found.get(ref)
    if previous is None:
        found[ref] = tagged
    elif previous != tagged:
        conflicts.append("%d:%d" % ref)


def load_book(url):
    """Get a book's verses, working down from strict parsing to salvage.

    Returns (verses, conflicts, how) where `how` names the route taken so the
    run can report which books needed help.
    """
    body = download(url)
    decoder = json.JSONDecoder()

    # raw_decode stops at the end of the first value, which also covers the
    # file that has a second JSON document concatenated onto it.
    try:
        raw, _ = decoder.raw_decode(body)
        return verses_from_structure(raw) + ("parsed",)
    except (json.JSONDecodeError, AttributeError, StopIteration):
        pass

    try:
        raw, _ = decoder.raw_decode(_BAD_ESCAPE.sub(r'\\"', body))
        return verses_from_structure(raw) + ("repaired",)
    except (json.JSONDecodeError, AttributeError, StopIteration):
        pass

    verses, conflicts = verses_from_english_only(body)
    if not verses:
        raise ValueError("no verses could be read")
    return verses, conflicts, "salvaged"


def plain(tagged):
    """Strip markers, for the verse-count and empty-verse checks."""
    return " ".join(_EM.sub("", _TAG.sub("", tagged)).split())


def build_module(found, display_name):
    """Turn {(chapter, verse): tagged text} into a module the app can read."""
    tag_kinds = set()
    verses = []
    for ref in sorted(found):
        tagged = found[ref]
        tag_kinds.update(m.group(1) for m in _TAG.finditer(tagged))
        verses.append({
            "chapter": ref[0],
            "verse": ref[1],
            "heading": None,
            "lines": [{"text": "", "poetry": False, "tagged": tagged}],
        })
    return {"book": display_name, "translation": "KJV", "verses": verses}, tag_kinds


def check(book_id, module, tag_kinds):
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
    if any(not plain(v["lines"][0]["tagged"]) for v in module["verses"]):
        problems.append("%s: at least one empty verse" % book_id)

    # The testament check.
    if book_id in OT_IDS and "G" in tag_kinds:
        problems.append("%s is Old Testament but carries Greek tags" % book_id)
    if book_id not in OT_IDS and "H" in tag_kinds:
        problems.append("%s is New Testament but carries Hebrew tags" % book_id)
    return problems


# Entities in this lexicon are written without their terminating semicolon
# ("&#8212" for an em dash, "&#39" for an apostrophe), so html.unescape alone
# does nothing. 94% of definitions contain at least one.
_ENTITY = re.compile(r"&#(\d+);?")
_NAMED_ENTITY = re.compile(r"&([A-Za-z]+);?")


def clean_text(raw):
    """Repair a lexicon field.

    Three defects, measured across all 12,040 entries:
      * 23% of definitions begin with a literal "null"
      * 94% contain numeric entities missing their semicolon
      * some are the same text written twice end to end (H430 among them)
    """
    if not raw:
        return ""

    text = _ENTITY.sub(lambda m: chr(int(m.group(1))), raw)
    text = _NAMED_ENTITY.sub(
        lambda m: html.unescape("&%s;" % m.group(1)), text)
    text = " ".join(text.split())

    if text.startswith("null"):
        text = text[4:].lstrip()

    return dedupe_repeat(text)


def dedupe_repeat(text):
    """Collapse a string that is one fragment written twice."""
    length = len(text)
    if length < 12:
        return text
    for split in (length // 2, (length + 1) // 2):
        first, second = text[:split].strip(), text[split:].strip()
        if first and first == second:
            return first
    return text


def write_lexicon(out_dir, raw):
    """Normalise and bucket by hundred: G2455 -> g24.json."""
    os.makedirs(out_dir, exist_ok=True)
    buckets = {}
    skipped = 0
    for key, entry in raw.items():
        match = re.fullmatch(r"([GH])(\d+)", key)
        if not match:
            skipped += 1
            continue
        prefix, number = match.group(1), int(match.group(2))
        buckets.setdefault("%s%d" % (prefix.lower(), number // BUCKET_SIZE), {})[key] = {
            "word": (entry.get("Gk_word") or entry.get("Hb_word") or "").strip(),
            "translit": clean_text(entry.get("transliteration", "")),
            "definition": clean_text(entry.get("strongs_def", "")),
            "pos": clean_text(entry.get("part_of_speech", "")),
            "root": clean_text(entry.get("root_word", "")),
            "usage": clean_text(entry.get("outline_usage", "")),
        }

    for name, entries in buckets.items():
        with open(os.path.join(out_dir, "%s.json" % name), "w", encoding="utf-8") as fh:
            json.dump(entries, fh, ensure_ascii=False, separators=(",", ":"))
    return len(buckets), sum(len(b) for b in buckets.values()), skipped


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--assets", required=True, help="tool/src/main/assets")
    ap.add_argument("--skip-lexicon", action="store_true")
    ap.add_argument("--lexicon-only", action="store_true",
                    help="rebuild just the lexicon, leaving the text alone")
    args = ap.parse_args(argv)

    bible_dir = os.path.join(args.assets, "bible", "kjv")
    lexicon_dir = os.path.join(args.assets, "lexicon")
    os.makedirs(bible_dir, exist_ok=True)

    problems = []
    repairs = []
    total = 0
    tagged_words = 0

    wanted = [] if args.lexicon_only else BOOKS
    for index, (source, book_id, display_name) in enumerate(wanted, 1):
        try:
            found, conflicts, how = load_book(BOOKS_URL.format(source))
            module, tag_kinds = build_module(found, display_name)
        except Exception as exc:
            problems.append("%s: could not be read (%s)" % (book_id, exc))
            continue

        if how != "parsed":
            repairs.append("%s (%s)" % (book_id, how))

        if conflicts:
            problems.append("%s: %d verses repeat with differing text (%s)"
                            % (book_id, len(conflicts), ", ".join(conflicts[:3])))
        problems.extend(check(book_id, module, tag_kinds))
        with open(os.path.join(bible_dir, "%s.json" % book_id), "w",
                  encoding="utf-8") as fh:
            json.dump(module, fh, ensure_ascii=False, separators=(",", ":"))

        total += len(module["verses"])
        tagged_words += sum(len(_TAG.findall(v["lines"][0]["tagged"]))
                            for v in module["verses"])
        print("  [%2d/66] %-16s %5d verses" % (index, display_name,
                                               len(module["verses"])),
              file=sys.stderr)

    if not args.lexicon_only:
        print("\n66 books, %d verses, %d tagged words" % (total, tagged_words),
              file=sys.stderr)
        if total == TOTAL_VERSES:
            print("verse total matches the KJV's %d" % TOTAL_VERSES, file=sys.stderr)
        else:
            problems.append("verse total is %d, expected %d" % (total, TOTAL_VERSES))

    if repairs:
        print("repaired a bad escape in: %s" % ", ".join(repairs), file=sys.stderr)

    if not args.skip_lexicon:
        try:
            files, entries, skipped = write_lexicon(
                lexicon_dir, json.loads(download(LEXICON_URL)))
            print("lexicon: %d entries in %d buckets (%d keys skipped)"
                  % (entries, files, skipped), file=sys.stderr)
        except Exception as exc:
            problems.append("lexicon: failed (%s)" % exc)

    for p in problems:
        print("  WARNING: %s" % p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
