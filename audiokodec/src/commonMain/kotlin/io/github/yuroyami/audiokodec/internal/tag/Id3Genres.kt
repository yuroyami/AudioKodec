package io.github.yuroyami.audiokodec.internal.tag

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
     * Turns whatever a `TCON` frame holds into a readable genre.
     *
     * The frame may hold a name, a bare number, a number in brackets, a number
     * in brackets followed by a name, or `RX` and `CR` for remix and cover.
     * Anything that resolves to a table entry becomes that entry's name, and
     * anything else is returned as it was.
     */
    fun resolve(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed

        // "(17)Rock" and "(17)": the text after the brackets wins when it is
        // there, because a writer that included both meant the words.
        if (trimmed.startsWith("(")) {
            val close = trimmed.indexOf(')')
            if (close > 1) {
                val inside = trimmed.substring(1, close)
                val after = trimmed.substring(close + 1).trim()
                if (after.isNotEmpty()) return after
                when (inside.uppercase()) {
                    "RX" -> return "Remix"
                    "CR" -> return "Cover"
                }
                inside.toIntOrNull()?.let { code -> nameOf(code)?.let { return it } }
                return trimmed
            }
        }

        // A bare number is a code, which is how ID3v2.4 writes one.
        trimmed.toIntOrNull()?.let { code -> nameOf(code)?.let { return it } }
        return trimmed
    }
}
