package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.tag.PictureType

/** The established APE cover-art keys, in ID3 picture-type order. */
internal object ApePictureKeys {
    private val names = listOf(
        "Other", "Png Icon", "Icon", "Front", "Back", "Leaflet", "Media",
        "Lead Artist", "Artist", "Conductor", "Band", "Composer", "Lyricist",
        "Recording Location", "During Recording", "During Performance",
        "Video Capture", "Fish", "Illustration", "Band Logotype", "Publisher Logotype",
    )

    fun key(type: PictureType): String = "Cover Art (${names[type.ordinal]})"

    fun type(key: String): PictureType = PictureType.entries.firstOrNull {
        this.key(it).equals(key, ignoreCase = true)
    } ?: if (key.contains("Back", ignoreCase = true)) PictureType.BACK_COVER else PictureType.FRONT_COVER
}
