package com.fourgeailabs.prismtune.data

/**
 * 1.1.0 A-Z jump for the Artists list. the user's library paginates to
 * 63 pages at 6 per page; tapping Next 62 times in a car is
 * unusable, so a letter jump goes straight to the first artist
 * starting with that letter. Pure, unit-testable.
 *
 * The jump letter is the first alphabetic character of the title,
 * after stripping a leading "The " (libraries sort that way), and
 * uppercased. Non-letters group under '#'. Returns -1 when no
 * artist starts with the letter — the UI then shows a plain
 * "No artists starting with X" state instead of jumping nowhere.
 */
fun artistJumpLetter(title: String): Char {
    val stripped = title.trim().removePrefix("The ").removePrefix("the ").trim()
    val first = stripped.firstOrNull { it.isLetter() } ?: return '#'
    return first.uppercaseChar()
}

fun artistJumpIndex(titles: List<String>, letter: Char): Int {
    val want = letter.uppercaseChar()
    return titles.indexOfFirst { artistJumpLetter(it) == want }
}

/** Page (6 per page, matching PagedList) containing [index], or -1. */
fun artistJumpPage(index: Int, pageSize: Int = 6): Int =
    if (index < 0) -1 else index / pageSize

/** The letter strip: A-Z plus '#' for non-letter titles. */
val ARTIST_JUMP_LETTERS: List<Char> = ('A'..'Z').toList() + '#'
