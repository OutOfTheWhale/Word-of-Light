package com.outofthewhale.wordoflight

/**
 * The 66 books, in order, with chapter counts.
 *
 * [Testament] is not decoration: the original-language features depend on it.
 * The Old Testament is Hebrew (with a little Aramaic) and the New is Greek, so
 * a word study has to know which lexicon it is allowed to reach for. Getting
 * this backwards is what produced Hebrew entries on New Testament words in the
 * earlier prototype.
 */
enum class Testament { OLD, NEW }

data class Book(
    val id: String,
    val name: String,
    val chapters: Int,
    val testament: Testament,
) {
    val isOldTestament: Boolean get() = testament == Testament.OLD
}

object Canon {
    private fun ot(id: String, name: String, chapters: Int) =
        Book(id, name, chapters, Testament.OLD)

    private fun nt(id: String, name: String, chapters: Int) =
        Book(id, name, chapters, Testament.NEW)

    val books: List<Book> = listOf(
        ot("gen", "Genesis", 50),
        ot("exo", "Exodus", 40),
        ot("lev", "Leviticus", 27),
        ot("num", "Numbers", 36),
        ot("deu", "Deuteronomy", 34),
        ot("jos", "Joshua", 24),
        ot("jdg", "Judges", 21),
        ot("rut", "Ruth", 4),
        ot("1sa", "1 Samuel", 31),
        ot("2sa", "2 Samuel", 24),
        ot("1ki", "1 Kings", 22),
        ot("2ki", "2 Kings", 25),
        ot("1ch", "1 Chronicles", 29),
        ot("2ch", "2 Chronicles", 36),
        ot("ezr", "Ezra", 10),
        ot("neh", "Nehemiah", 13),
        ot("est", "Esther", 10),
        ot("job", "Job", 42),
        ot("psa", "Psalms", 150),
        ot("pro", "Proverbs", 31),
        ot("ecc", "Ecclesiastes", 12),
        ot("sng", "Song of Songs", 8),
        ot("isa", "Isaiah", 66),
        ot("jer", "Jeremiah", 52),
        ot("lam", "Lamentations", 5),
        ot("ezk", "Ezekiel", 48),
        ot("dan", "Daniel", 12),
        ot("hos", "Hosea", 14),
        ot("jol", "Joel", 3),
        ot("amo", "Amos", 9),
        ot("oba", "Obadiah", 1),
        ot("jon", "Jonah", 4),
        ot("mic", "Micah", 7),
        ot("nam", "Nahum", 3),
        ot("hab", "Habakkuk", 3),
        ot("zep", "Zephaniah", 3),
        ot("hag", "Haggai", 2),
        ot("zec", "Zechariah", 14),
        ot("mal", "Malachi", 4),
        nt("mat", "Matthew", 28),
        nt("mrk", "Mark", 16),
        nt("luk", "Luke", 24),
        nt("jhn", "John", 21),
        nt("act", "Acts", 28),
        nt("rom", "Romans", 16),
        nt("1co", "1 Corinthians", 16),
        nt("2co", "2 Corinthians", 13),
        nt("gal", "Galatians", 6),
        nt("eph", "Ephesians", 6),
        nt("php", "Philippians", 4),
        nt("col", "Colossians", 4),
        nt("1th", "1 Thessalonians", 5),
        nt("2th", "2 Thessalonians", 3),
        nt("1ti", "1 Timothy", 6),
        nt("2ti", "2 Timothy", 4),
        nt("tit", "Titus", 3),
        nt("phm", "Philemon", 1),
        nt("heb", "Hebrews", 13),
        nt("jas", "James", 5),
        nt("1pe", "1 Peter", 5),
        nt("2pe", "2 Peter", 3),
        nt("1jn", "1 John", 5),
        nt("2jn", "2 John", 1),
        nt("3jn", "3 John", 1),
        nt("jud", "Jude", 1),
        nt("rev", "Revelation", 22),
    )

    private val byId = books.associateBy { it.id }

    fun book(id: String): Book? = byId[id]

    fun next(ref: ChapterRef): ChapterRef? {
        val book = book(ref.book) ?: return null
        if (ref.chapter < book.chapters) return ref.copy(chapter = ref.chapter + 1)
        val index = books.indexOf(book)
        if (index == books.lastIndex) return null
        return ChapterRef(books[index + 1].id, 1)
    }

    fun previous(ref: ChapterRef): ChapterRef? {
        val book = book(ref.book) ?: return null
        if (ref.chapter > 1) return ref.copy(chapter = ref.chapter - 1)
        val index = books.indexOf(book)
        if (index == 0) return null
        val prior = books[index - 1]
        return ChapterRef(prior.id, prior.chapters)
    }
}
