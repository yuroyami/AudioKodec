package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat

/** Limits of the native text projections that accompany complete ID3 metadata. */
internal object NativeTextCapabilities {
    fun fits(field: TagField, value: String): Boolean =
        TagText.isLatin1(value) && '\u0000' !in value &&
            (field != TagField.COMMENT || value == value.trim())

    fun losses(tags: TagBundle, format: TagFormat): List<String> {
        val missing = ArrayList<String>()
        for ((field, values) in tags.fields) {
            val nonEmpty = values.filter { it.isNotEmpty() }
            if (nonEmpty.isEmpty()) continue
            val key = if (format == TagFormat.RIFF_INFO) {
                TagMapping.riffInfoKeyFor(field)
            } else {
                TagMapping.aiffChunkFor(field)
            }
            when {
                key == null -> missing.add(field.name)
                nonEmpty.any { !fits(field, it) } -> missing.add("${field.name} ($key text)")
                format == TagFormat.AIFF_TEXT && key != "ANNO" && nonEmpty.distinct().size > 1 ->
                    missing.add("${field.name} ($key, multiple values)")
            }
        }
        if (tags.pictures.isNotEmpty()) missing.add("pictures")
        if (tags.chapters.isNotEmpty()) missing.add("chapters")
        if (tags.replayGain != null) missing.add("ReplayGain")
        return missing
    }

    fun requireComplete(tags: TagBundle, format: TagFormat) {
        val missing = losses(tags, format)
        if (missing.isNotEmpty()) {
            throw UnsupportedFeatureException(
                "${format.displayName} cannot retain ${missing.joinToString()}",
                "${format.displayName} cannot retain ${missing.joinToString()}. " +
                    "Include ID3v2, or use the default format selection.",
            )
        }
    }
}
