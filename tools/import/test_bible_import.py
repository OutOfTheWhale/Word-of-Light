#!/usr/bin/env python3
"""Tests for bible_import, using synthetic text shaped like the real paste."""

import unittest

from bible_import import find_verse_number, is_heading, parse, render


def verses_of(result, chapter):
    return [v for v in result["verses"] if v["chapter"] == chapter]


def text_of(result, chapter, verse):
    for v in result["verses"]:
        if v["chapter"] == chapter and v["verse"] == verse:
            return render(v)
    raise AssertionError("no verse %d:%d" % (chapter, verse))


# Mirrors the real shape: trailing spaces, unnumbered verse 1, a mid-chapter
# heading, several verses packed onto one line, a genealogy full of numbers,
# and an indented poetry block whose first line carries the verse number.
SAMPLE = """Chapter 1
The Opening

An unnumbered first verse.

2 A second verse. 3 A third one. 4 And a fourth.

5 He was 130 years old when the second thing happened, and he lived 800 years. 6 The next one.

Halfway Along

7 After the heading.

 8 A poetry line;
 a second poetry line,
 and a third.

9 Back to prose.
Chapter 2

Only one verse in here.
 -- Testbook 1:1-2:1 (XYZ)
"""


class TrailerTests(unittest.TestCase):
    def test_book_and_translation_come_from_the_trailer(self):
        result = parse(SAMPLE)
        self.assertEqual(result["book"], "Testbook")
        self.assertEqual(result["translation"], "XYZ")

    def test_trailer_is_not_parsed_as_verse_text(self):
        self.assertNotIn("XYZ", text_of(parse(SAMPLE), 2, 1))


class ChapterTests(unittest.TestCase):
    def test_verse_one_is_unnumbered(self):
        self.assertEqual(text_of(parse(SAMPLE), 1, 1), "An unnumbered first verse.")

    def test_a_chapter_with_a_single_unnumbered_verse(self):
        result = parse(SAMPLE)
        self.assertEqual(len(verses_of(result, 2)), 1)
        self.assertEqual(text_of(result, 2, 1), "Only one verse in here.")

    def test_several_verses_on_one_line_are_split(self):
        result = parse(SAMPLE)
        self.assertEqual(text_of(result, 1, 2), "A second verse.")
        self.assertEqual(text_of(result, 1, 3), "A third one.")
        self.assertEqual(text_of(result, 1, 4), "And a fourth.")

    def test_all_verses_are_found(self):
        self.assertEqual([v["verse"] for v in verses_of(parse(SAMPLE), 1)],
                         list(range(1, 10)))


class NumbersInTextTests(unittest.TestCase):
    """The trap: a genealogy verse is mostly numbers."""

    def test_numbers_inside_a_verse_are_not_treated_as_verse_numbers(self):
        body = text_of(parse(SAMPLE), 1, 5)
        self.assertIn("130 years old", body)
        self.assertIn("lived 800 years", body)

    def test_the_verse_after_a_number_heavy_one_still_starts_correctly(self):
        self.assertEqual(text_of(parse(SAMPLE), 1, 6), "The next one.")

    def test_only_the_next_expected_number_can_split(self):
        line = "he was 8 years old and had 7 sons"
        self.assertIsNone(find_verse_number(line, 3))
        self.assertIsNotNone(find_verse_number(line, 8))

    def test_a_number_inside_a_larger_number_never_matches(self):
        self.assertIsNone(find_verse_number("he lived 130 years", 13))
        self.assertIsNone(find_verse_number("he lived 130 years", 30))

    def test_a_number_glued_to_a_word_never_matches(self):
        self.assertIsNone(find_verse_number("chapter5 begins", 5))

    def test_a_verse_number_may_precede_an_opening_quote(self):
        self.assertIsNotNone(find_verse_number('said. 12 “Get out”', 12))


class HeadingTests(unittest.TestCase):
    def test_a_heading_attaches_to_the_verse_that_follows_it(self):
        result = parse(SAMPLE)
        self.assertEqual(text_of(result, 1, 1) and verses_of(result, 1)[0]["heading"],
                         "The Opening")

    def test_a_heading_can_appear_mid_chapter(self):
        seventh = [v for v in verses_of(parse(SAMPLE), 1) if v["verse"] == 7][0]
        self.assertEqual(seventh["heading"], "Halfway Along")

    def test_a_heading_is_not_swallowed_into_the_previous_verse(self):
        self.assertNotIn("Halfway", text_of(parse(SAMPLE), 1, 6))

    def test_prose_is_never_mistaken_for_a_heading(self):
        self.assertFalse(is_heading("An unnumbered first verse.", False))

    def test_an_indented_line_is_never_a_heading(self):
        self.assertFalse(is_heading("A poetry line", True))

    def test_a_line_ending_in_a_colon_is_not_a_heading(self):
        self.assertFalse(is_heading("Then the Lord said to Abram:", False))

    def test_a_long_line_is_not_a_heading(self):
        self.assertFalse(is_heading("x" * 80, False))


class PoetryTests(unittest.TestCase):
    def test_poetry_keeps_its_line_breaks(self):
        self.assertEqual(text_of(parse(SAMPLE), 1, 8).count("\n"), 2)

    def test_prose_is_joined_with_spaces(self):
        self.assertNotIn("\n", text_of(parse(SAMPLE), 1, 5))

    def test_a_poetry_block_carries_the_verse_number_on_its_first_line(self):
        body = text_of(parse(SAMPLE), 1, 8)
        self.assertTrue(body.startswith("A poetry line;"))
        self.assertTrue(body.endswith("and a third."))

    def test_the_verse_after_a_poetry_block_is_prose_again(self):
        self.assertEqual(text_of(parse(SAMPLE), 1, 9), "Back to prose.")


class ValidationTests(unittest.TestCase):
    def test_a_clean_parse_reports_no_warnings(self):
        self.assertEqual(parse(SAMPLE)["warnings"], [])

    def test_a_dropped_verse_marker_is_reported(self):
        # Verse 3's marker is missing. The parser cannot find it - it only ever
        # hunts for the next expected number - so "4 Fourth." lands inside verse
        # 2 and the numbering it produces (1, 2) is perfectly contiguous. Only
        # the orphan-number scan notices.
        broken = "Chapter 1 \n\nFirst. \n2 Second. \n4 Fourth. \n"
        warnings = parse(broken, book="Nowhere")["warnings"]
        self.assertTrue(any("marker was probably dropped" in w for w in warnings),
                        warnings)

    def test_a_dropped_marker_does_not_show_up_as_a_contiguity_gap(self):
        broken = "Chapter 1 \n\nFirst. \n2 Second. \n4 Fourth. \n"
        warnings = parse(broken, book="Nowhere")["warnings"]
        self.assertFalse(any("not contiguous" in w for w in warnings), warnings)

    def test_a_genealogy_does_not_trip_the_orphan_scan(self):
        # Large numbers in the text sit well clear of the next few verse
        # numbers, so they must not be reported.
        ok = ("Chapter 1 \n\nFirst. \n"
              "2 He was 130 years old and lived 800 years. \n"
              "3 The ark was 450 feet long, 75 feet wide, and 45 feet high. \n"
              "4 He assembled his 318 trained men. \n")
        self.assertEqual(parse(ok, book="Nowhere")["warnings"], [])

    def test_a_wrong_verse_count_is_reported_against_the_reference(self):
        short = "Chapter 1 \n\nFirst. \n2 Second. \n -- Genesis 1:1-1:2 (KJV)\n"
        warnings = parse(short)["warnings"]
        self.assertTrue(any("expected 31" in w for w in warnings), warnings)

    def test_the_trailer_range_is_checked_against_what_was_parsed(self):
        short = "Chapter 1 \n\nFirst. \n2 Second. \n -- Testbook 1:1-1:9 (XYZ)\n"
        warnings = parse(short)["warnings"]
        self.assertTrue(any("trailer says 9" in w for w in warnings), warnings)


if __name__ == "__main__":
    unittest.main(verbosity=2)
