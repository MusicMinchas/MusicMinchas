package com.kike.musicplayer.data

/** What is typed in the search box, as a full-text query. */
object SearchText {
    private val SEPARATORS = Regex("""[^\p{L}\p{N}]+""")

    /**
     * Every word typed must start a word of the title ("lost holly" finds "Lost in Hollywood");
     * punctuation separates words and can't be mistaken for query syntax. Null when nothing
     * searchable was typed.
     */
    fun match(text: String): String? {
        val words = text.lowercase().split(SEPARATORS).filter { it.isNotEmpty() }
        return if (words.isEmpty()) null else words.joinToString(" ") { "$it*" }
    }
}
