#!/usr/bin/env python3
"""Convert pasted Bible chapter text into structured verse records.

Input is the shape you get by copying a book out of a chapter-by-chapter web
reader:

    Chapter 1
    The Creation

    In the beginning God created the heavens and the earth.

    2 Now the earth was formless and empty, ... 3 Then God said, ...

Four rules the parser leans on:

  * "Chapter N" alone on a line starts a chapter.
  * Verse 1 carries NO number - it is simply the first body text in the
    chapter. Every later verse is numbered inline.
  * Verse numbers are strictly sequential, and that is the only reliable way to
    tell one from a number inside the text. A genealogy reads "Adam was 130
    years old when he fathered a son ... 4 Adam lived 800 years" - splitting on
    every integer would shred it. We only ever look for the next expected
    number, so 130 and 800 are left alone.
  * Section headings sit unindented on their own line, run short, and carry no
    sentence-ending punctuation. They can appear mid-chapter, not just at the
    top.

Indented lines are poetry and keep their line breaks.

Usage:
    python bible_import.py genesis.txt -o genesis.json
    python bible_import.py genesis.txt --book Genesis --translation CSB
"""

import argparse
import json
import re
import sys

# --- reference verse counts, used to validate a parse ------------------------
# Only books you have actually converted need an entry. A missing book just
# skips the count check and relies on the contiguity check instead.
VERSE_COUNTS = {
    "Genesis": [31, 25, 24, 26, 32, 22, 24, 22, 29, 32, 32, 20, 18, 24, 21, 16,
                27, 33, 38, 18, 34, 24, 20, 67, 34, 35, 46, 22, 35, 43, 55, 32,
                20, 31, 29, 43, 36, 30, 23, 23, 57, 38, 34, 34, 28, 34, 31, 22,
                33, 26],
}

_CHAPTER = re.compile(r"^\s*Chapter\s+(\d+)\s*$")
_TRAILER = re.compile(
    r"--\s*([1-3]?\s*[A-Za-z]+(?:\s+[A-Za-z]+)*?)\s+"
    r"(\d+):(\d+)\s*[-–]\s*(\d+):(\d+)\s*\(([A-Za-z0-9]+)\)\s*$"
)

# A heading never ends on one of these.
_SENTENCE_END = set(".!?;:,\"”’‘“)")
_MAX_HEADING = 60


def normalize(text):
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = text.replace(" ", " ")      # non-breaking spaces from the web
    text = text.replace(" ", "\n")
    return text


def parse_trailer(text):
    """Pull book / range / translation from a '-- Genesis 1:1-50:26 (CSB)' line."""
    for line in reversed(text.strip().split("\n")):
        line = line.strip()
        if not line:
            continue
        m = _TRAILER.search(line)
        if m:
            return {
                "book": re.sub(r"\s+", " ", m.group(1)).strip(),
                "start_chapter": int(m.group(2)),
                "start_verse": int(m.group(3)),
                "end_chapter": int(m.group(4)),
                "end_verse": int(m.group(5)),
                "translation": m.group(6),
            }
        break
    return None


def is_heading(stripped, indented):
    """Heading heuristic. Conservative, and every hit is reported for review."""
    if indented or not stripped:
        return False
    if stripped[0].isdigit():
        return False
    if len(stripped) > _MAX_HEADING:
        return False
    if stripped[-1] in _SENTENCE_END:
        return False
    return True


def find_verse_number(text, want, start=0):
    """Find `want` as a standalone token - not inside 130, not inside 1:1."""
    pattern = re.compile(r"(?<!\d)" + str(want) + r"(?!\d)")
    for m in pattern.finditer(text, start):
        before = text[:m.start()]
        after = text[m.end():]
        if before and not before[-1].isspace():
            continue
        if after and not (after[0].isspace() or after[0] in "“\"‘"):
            continue
        return m
    return None


class _ChapterBuilder:
    def __init__(self, chapter):
        self.chapter = chapter
        self.verses = []
        self.current = None
        self.pending_heading = None

    def start_verse(self, number):
        verse = {
            "chapter": self.chapter,
            "verse": number,
            "heading": self.pending_heading,
            "lines": [],
        }
        self.pending_heading = None
        self.verses.append(verse)
        self.current = verse
        return verse

    def add_text(self, text, poetry):
        text = text.strip()
        if not text or self.current is None:
            return
        self.current["lines"].append({"text": text, "poetry": poetry})

    def feed(self, text, poetry):
        """Split one source line across however many verses start inside it."""
        if self.current is None:
            self.start_verse(1)          # verse 1 is unnumbered
        pos = 0
        while True:
            want = self.current["verse"] + 1
            m = find_verse_number(text, want, pos)
            if m is None:
                self.add_text(text[pos:], poetry)
                return
            self.add_text(text[pos:m.start()], poetry)
            self.start_verse(want)
            pos = m.end()


def parse(text, book=None, translation=None):
    text = normalize(text)
    trailer = parse_trailer(text)
    if trailer:
        book = book or trailer["book"]
        translation = translation or trailer["translation"]

    warnings = []
    headings = []
    chapters = []
    builder = None

    for raw in text.split("\n"):
        line = raw.rstrip()
        stripped = line.strip()
        if not stripped:
            continue

        m = _CHAPTER.match(stripped)
        if m:
            if builder is not None:
                chapters.append(builder)
            builder = _ChapterBuilder(int(m.group(1)))
            continue

        if trailer and _TRAILER.search(stripped):
            continue                      # the attribution line at the end

        if builder is None:
            warnings.append("text before the first 'Chapter' line: %r" % stripped[:60])
            continue

        indented = bool(raw) and raw[0].isspace()
        if is_heading(stripped, indented):
            builder.pending_heading = stripped
            headings.append((builder.chapter, stripped))
            continue

        builder.feed(stripped, indented)

    if builder is not None:
        chapters.append(builder)

    verses = []
    for ch in chapters:
        for v in ch.verses:
            if not v["lines"]:
                warnings.append("empty verse %d:%d" % (v["chapter"], v["verse"]))
            verses.append(v)

    warnings.extend(validate(chapters, book, trailer))

    return {
        "book": book,
        "translation": translation,
        "verses": verses,
        "headings": headings,
        "warnings": warnings,
    }


def find_dropped_markers(chapter):
    """Catch a verse marker that went missing from the source.

    Because we only ever hunt for the next expected number, a marker dropped
    during copying is never found - its text is silently appended to the
    previous verse, and the contiguity check still passes, because the
    numbering we produced is internally consistent. Nothing else notices.

    So: look inside each verse for a standalone integer just above its own
    number that did not become a verse. Real text sits well clear of this
    window - a genealogy says "lived 800 years" at verse 5, not "6".
    """
    problems = []
    seen = {v["verse"] for v in chapter.verses}
    for verse in chapter.verses:
        body = render(verse)
        for n in range(verse["verse"] + 1, verse["verse"] + 4):
            if n in seen:
                continue
            if find_verse_number(body, n):
                problems.append(
                    "chapter %d verse %d contains a standalone '%d' - a verse "
                    "marker was probably dropped when this was copied"
                    % (chapter.chapter, verse["verse"], n)
                )
                break
    return problems


def validate(chapters, book, trailer):
    problems = []
    numbers = [c.chapter for c in chapters]
    if numbers != list(range(1, len(numbers) + 1)):
        problems.append("chapter numbers are not 1..N: %s" % numbers)

    expected = VERSE_COUNTS.get(book or "")
    for ch in chapters:
        got = [v["verse"] for v in ch.verses]
        problems.extend(find_dropped_markers(ch))
        if got != list(range(1, len(got) + 1)):
            missing = sorted(set(range(1, max(got) + 1)) - set(got)) if got else []
            problems.append(
                "chapter %d verses are not contiguous (missing %s)" % (ch.chapter, missing)
            )
        if expected and ch.chapter <= len(expected):
            want = expected[ch.chapter - 1]
            if len(got) != want:
                problems.append(
                    "chapter %d has %d verses, expected %d" % (ch.chapter, len(got), want)
                )

    if trailer and chapters:
        last = chapters[-1]
        if last.chapter != trailer["end_chapter"]:
            problems.append("last chapter %d, trailer says %d"
                            % (last.chapter, trailer["end_chapter"]))
        elif last.verses and last.verses[-1]["verse"] != trailer["end_verse"]:
            problems.append("last verse %d, trailer says %d"
                            % (last.verses[-1]["verse"], trailer["end_verse"]))
    return problems


def render(verse):
    """Flatten a verse back to display text - poetry lines keep their breaks."""
    out = []
    for i, line in enumerate(verse["lines"]):
        if i:
            out.append("\n" if line["poetry"] else " ")
        out.append(line["text"])
    return "".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input")
    ap.add_argument("-o", "--output")
    ap.add_argument("--book")
    ap.add_argument("--translation")
    ap.add_argument("--show-headings", action="store_true",
                    help="print every line classified as a section heading")
    args = ap.parse_args(argv)

    with open(args.input, encoding="utf-8") as fh:
        result = parse(fh.read(), args.book, args.translation)

    chapters = len({v["chapter"] for v in result["verses"]})
    print("%s (%s): %d chapters, %d verses, %d headings"
          % (result["book"], result["translation"], chapters,
             len(result["verses"]), len(result["headings"])), file=sys.stderr)

    if args.show_headings:
        for chapter, heading in result["headings"]:
            print("  %3d  %s" % (chapter, heading), file=sys.stderr)

    for w in result["warnings"]:
        print("  WARNING: %s" % w, file=sys.stderr)
    if not result["warnings"]:
        print("  validation clean", file=sys.stderr)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            json.dump(result, fh, ensure_ascii=False, indent=1)
        print("  wrote %s" % args.output, file=sys.stderr)

    return 1 if result["warnings"] else 0


if __name__ == "__main__":
    sys.exit(main())
