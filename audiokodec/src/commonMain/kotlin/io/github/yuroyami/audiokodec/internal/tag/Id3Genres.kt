package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.UnsupportedFeatureException

/**
 * The numbered genre list ID3v1 defined and Winamp extended twice.
 *
 * ID3v1 has one byte for the genre, so it needs a table. ID3v2 kept the table
 * for compatibility: a `TCON` frame may hold `17`, or `(17)`, or `(17)Rock`,
 * and all three mean the same thing.
 *
 * Entry 133 is written here as "Afro-Punk". The historical list used a racial
 * slur in that slot, which this project will not reproduce. No file in
 * practice uses the code, and a reader that meets one gets a sensible name.
 */
internal object Id3Genres {

    val NAMES: Array<String> = arrayOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge",
        "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B",
        "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska",
        "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient",
        "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical",
        "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel",
        "Noise", "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative",
        "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk",
        "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American",
        "Cabaret", "New Wave", "Psychadelic", "Rave", "Showtunes", "Trailer",
        "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro",
        "Musical", "Rock & Roll", "Hard Rock", "Folk", "Folk-Rock",
        "National Folk", "Swing", "Fast Fusion", "Bebob", "Latin", "Revival",
        "Celtic", "Bluegrass", "Avantgarde", "Gothic Rock", "Progressive Rock",
        "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band",
        "Chorus", "Easy Listening", "Acoustic", "Humour", "Speech", "Chanson",
        "Opera", "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus",
        "Porn Groove", "Satire", "Slow Jam", "Club", "Tango", "Samba",
        "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
        "Duet", "Punk Rock", "Drum Solo", "A Cappella", "Euro-House",
        "Dance Hall", "Goa", "Drum & Bass", "Club-House", "Hardcore", "Terror",
        "Indie", "BritPop", "Afro-Punk", "Polsk Punk", "Beat",
        "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover",
        "Contemporary Christian", "Christian Rock", "Merengue", "Salsa",
        "Thrash Metal", "Anime", "JPop", "Synthpop", "Abstract", "Art Rock",
        "Baroque", "Bhangra", "Big Beat", "Breakbeat", "Chillout", "Downtempo",
        "Dub", "EBM", "Eclectic", "Electro", "Electroclash", "Emo",
        "Experimental", "Garage", "Global", "IDM", "Illbient", "Industro-Goth",
        "Jam Band", "Krautrock", "Leftfield", "Lounge", "Math Rock",
        "New Romantic", "Nu-Breakz", "Post-Punk", "Post-Rock", "Psytrance",
        "Shoegaze", "Space Rock", "Trop Rock", "World Music", "Neoclassical",
        "Audiobook", "Audio Theatre", "Neue Deutsche Welle", "Podcast",
        "Indie Rock", "G-Funk", "Dubstep", "Garage Rock", "Psybient",
    )

    /** The name for a genre code, or null when the code is not in the table. */
    fun nameOf(code: Int): String? = NAMES.getOrNull(code)

    /** The code for a genre name, or -1 when the name is not in the table. */
    fun codeOf(name: String): Int {
        for (i in NAMES.indices) {
            if (NAMES[i].equals(name, ignoreCase = true)) return i
        }
        return -1
    }

    /**
     * Resolves the leading reference run and its optional literal refinement.
     *
     * V2.2 and v2.3 use brackets; v2.4 uses separate numeric strings. Older
     * bracket syntax is also accepted in v2.4 for compatibility. Unknown
     * syntax ends the run and remains literal, rather than becoming "Unknown".
     */
    fun resolveAll(value: String): List<String> {
        val text = value.trim()
        if (text.isEmpty()) return emptyList()
        val genres = LinkedHashSet<String>()
        var at = 0
        while (at < text.length && text[at] == '(') {
            if (at + 1 < text.length && text[at + 1] == '(') {
                genres.add(text.substring(at + 1))
                return genres.toList()
            }
            val close = text.indexOf(')', at + 1)
            if (close < 0) break
            val name = referenceName(text.substring(at + 1, close)) ?: break
            genres.add(name)
            at = close + 1
        }
        if (at < text.length) {
            val refinement = text.substring(at).trim()
            // Only a standalone string is a bare code. A numeric refinement
            // after a bracket run is free text, such as "(17)17".
            if (refinement.isNotEmpty()) {
                genres.add(if (at == 0) referenceName(refinement) ?: refinement else refinement)
            }
        }
        return genres.toList()
    }

    private fun referenceName(value: String): String? = when (value) {
        "RX" -> "Remix"
        "CR" -> "Cover"
        else -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
            value.toIntOrNull()?.let { nameOf(it) }
        } else null
    }

    /** Encodes named genres without changing their meaning or order. */
    fun storedValues(values: List<String>, major: Int): List<String> {
        val distinct = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinct.any { '\u0000' in it }) unsupported("a null inside a genre value")
        if (major == 4) return distinct.map { value ->
            if (referenceName(value) != null) unsupported("a literal reserved genre code '$value'")
            escapeRefinement(value)
        }
        val stored = StringBuilder()
        for ((index, value) in distinct.withIndex()) {
            val code = codeOf(value)
            val reference = when {
                code >= 0 && nameOf(code) == value -> code.toString()
                value == "Remix" -> "RX"
                value == "Cover" -> "CR"
                else -> null
            }
            if (reference != null) {
                stored.append('(').append(reference).append(')')
            } else {
                if (index != distinct.lastIndex) unsupported("several or interleaved free-text genres in ID3v2.3")
                if (stored.isEmpty() && referenceName(value) != null) unsupported("a literal reserved genre code '$value'")
                stored.append(escapeRefinement(value))
            }
        }
        return listOf(stored.toString())
    }

    // Retain the older escape in v2.4 too, for readers that accept legacy
    // reference syntax there. Otherwise a literal "(17)" becomes "Rock".
    private fun escapeRefinement(value: String): String = if (value.startsWith("(")) "($value" else value

    private fun unsupported(detail: String): Nothing = throw UnsupportedFeatureException(
        "representing ID3 genres",
        "ID3 cannot preserve $detail unambiguously; use named genres or ID3v2.4 for supported free-text lists",
    )
}
