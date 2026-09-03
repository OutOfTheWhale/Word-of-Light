#!/usr/bin/env python3
"""Build the reverse index that answers "where else does this word appear?".

The word study needs to go from a Strong's number to every verse using it. Done
at read time that means scanning ~9 MB of text on every tap, which the phone
cannot do without a visible stall. So it is precomputed here, once, and
bucketed the same way as the lexicon: G2316 lives in concordance/g23.json.

Reads the already-imported modules rather than re-fetching, so it can be re-run
cheaply whenever the text changes.

Output:
    assets/concordance/<bucket>.json   {"G2316": ["mat.1.23", "mrk.1.1", ...]}

References are "<book>.<chapter>.<verse>" in canonical order.

Usage:
    python build_concordance.py --assets ../../tool/src/main/assets
"""

import argparse
import collections
import json
import os
import re
import sys

BUCKET_SIZE = 100
_TAG = re.compile(r"\[([GH])(\d+)]")

# Canonical order, so a word's occurrences read Genesis-to-Revelation.
ORDER = [
    "gen", "exo", "lev", "num", "deu", "jos", "jdg", "rut", "1sa", "2sa",
    "1ki", "2ki", "1ch", "2ch", "ezr", "neh", "est", "job", "psa", "pro",
    "ecc", "sng", "isa", "jer", "lam", "ezk", "dan", "hos", "jol", "amo",
    "oba", "jon", "mic", "nam", "hab", "zep", "hag", "zec", "mal", "mat",
    "mrk", "luk", "jhn", "act", "rom", "1co", "2co", "gal", "eph", "php",
    "col", "1th", "2th", "1ti", "2ti", "tit", "phm", "heb", "jas", "1pe",
    "2pe", "1jn", "2jn", "3jn", "jud", "rev",
]
POSITION = {book: index for index, book in enumerate(ORDER)}
OLD_TESTAMENT = set(ORDER[:39])


def collect(bible_dir):
    """Map each Strong's number to the verses that use it."""
    index = collections.defaultdict(list)
    verses = 0
    tags = 0
    crossed = []

    for book in ORDER:
        path = os.path.join(bible_dir, "%s.json" % book)
        if not os.path.isfile(path):
            print("  missing %s" % path, file=sys.stderr)
            continue
        with open(path, encoding="utf-8") as fh:
            module = json.load(fh)

        is_old = book in OLD_TESTAMENT
        for verse in module["verses"]:
            verses += 1
            ref = "%s.%d.%d" % (book, verse["chapter"], verse["verse"])
            seen_here = set()
            for line in verse["lines"]:
                for match in _TAG.finditer(line.get("tagged") or ""):
                    number = "%s%s" % (match.group(1), match.group(2))
                    tags += 1

                    # An Old Testament verse carrying a Greek number, or the
                    # reverse, means the source is wrong. Indexing it would put
                    # the mistake in front of the reader as a real result.
                    if (match.group(1) == "G") == is_old:
                        crossed.append("%s %s" % (ref, number))
                        continue

                    # A word repeated in one verse should list that verse once.
                    if number in seen_here:
                        continue
                    seen_here.add(number)
                    index[number].append(ref)

    return index, verses, tags, crossed


def bucket_of(number):
    return "%s%d" % (number[0].lower(), int(number[1:]) // BUCKET_SIZE)


def write(index, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    buckets = collections.defaultdict(dict)
    for number, refs in index.items():
        refs.sort(key=lambda ref: (POSITION[ref.split(".")[0]],
                                   int(ref.split(".")[1]),
                                   int(ref.split(".")[2])))
        buckets[bucket_of(number)][number] = refs

    for name, entries in buckets.items():
        path = os.path.join(out_dir, "%s.json" % name)
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(entries, fh, ensure_ascii=False, separators=(",", ":"))
    return len(buckets)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--assets", required=True)
    args = ap.parse_args(argv)

    bible_dir = os.path.join(args.assets, "bible", "kjv")
    out_dir = os.path.join(args.assets, "concordance")

    index, verses, tags, crossed = collect(bible_dir)
    files = write(index, out_dir)

    total_refs = sum(len(refs) for refs in index.values())
    print("%d verses scanned, %d tags" % (verses, tags), file=sys.stderr)
    print("%d distinct Strong's numbers, %d verse references, %d buckets"
          % (len(index), total_refs, files), file=sys.stderr)

    commonest = sorted(index.items(), key=lambda kv: -len(kv[1]))[:3]
    for number, refs in commonest:
        print("  %-6s %5d verses" % (number, len(refs)), file=sys.stderr)

    if crossed:
        print("  WARNING: %d tags on the wrong testament, e.g. %s"
              % (len(crossed), ", ".join(crossed[:3])), file=sys.stderr)
    return 1 if crossed else 0


if __name__ == "__main__":
    sys.exit(main())
