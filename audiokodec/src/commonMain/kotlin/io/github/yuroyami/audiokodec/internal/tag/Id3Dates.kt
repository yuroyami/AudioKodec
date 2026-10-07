package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.tag.RawTagBlock
import io.github.yuroyami.audiokodec.tag.RawTags
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagFormat

/** Converts the native ID3 date fields without adding or discarding precision. */
internal object Id3Dates {
    fun isId3(format: TagFormat): Boolean =
        format == TagFormat.ID3V2_2 || format == TagFormat.ID3V2_3 || format == TagFormat.ID3V2_4

    fun isYear(key: String): Boolean = key == "TYER" || key == "TYE"

    fun isAuxiliary(key: String): Boolean = when (key) {
        "TDAT", "TIME", "TRDA", "TDA", "TIM", "TRD" -> true
        else -> false
    }

    fun auxiliaryKey(key: String): String = when (key) {
        "TDA" -> "TDAT"
        "TIM" -> "TIME"
        "TRD" -> "TRDA"
        else -> key
    }

    /** A legacy group is combined only when every component is unambiguous. */
    fun combined(block: RawTagBlock): String? {
        if (!isId3(block.format)) return null
        val year = Component()
        val dayMonth = Component()
        val time = Component()
        for (entry in block.entries) {
            val component = when (entry.key) {
                "TYER", "TYE" -> year
                "TDAT", "TDA" -> dayMonth
                "TIME", "TIM" -> time
                else -> continue
            }
            component.add(entry.text, entry.id3FrameFlags != null)
        }
        val yearText = year.single() ?: return null
        val yearNumber = digits(yearText, 0, 4) ?: return null
        if (yearText.length != 4) return null
        if (!dayMonth.present) return if (time.present) null else yearText
        val dateText = dayMonth.single() ?: return null
        if (dateText.length != 4) return null
        val day = digits(dateText, 0, 2) ?: return null
        val month = digits(dateText, 2, 2) ?: return null
        if (!validDay(yearNumber, month, day)) return null
        val date = "$yearText-${dateText.substring(2, 4)}-${dateText.substring(0, 2)}"
        if (!time.present) return date
        val timeText = time.single() ?: return null
        if (timeText.length != 4) return null
        val hour = digits(timeText, 0, 2) ?: return null
        val minute = digits(timeText, 2, 2) ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return "${date}T${timeText.substring(0, 2)}:${timeText.substring(2, 4)}"
    }

    /** Four-digit years, whole dates and minute precision fit the v2.3 fields. */
    fun legacyFields(field: TagField, values: List<String>): List<Pair<String, String>> {
        val value = values.first()
        if (values.any { it != value }) refuse(field, "multiple dates")
        if (field == TagField.ORIGINAL_DATE) {
            if (value.length != 4 || digits(value, 0, 4) == null) {
                refuse(field, "precision beyond a four-digit original-release year")
            }
            return listOf("TORY" to value)
        }
        if (value.length != 4 && value.length != 10 && value.length != 16) {
            refuse(field, "date precision that TYER/TDAT/TIME cannot retain")
        }
        val year = digits(value, 0, 4) ?: refuse(field, "a non-numeric four-digit year")
        val frames = arrayListOf("TYER" to value.substring(0, 4))
        if (value.length == 4) return frames
        if (value[4] != '-' || value[7] != '-') refuse(field, "a date outside yyyy-MM-dd")
        val month = digits(value, 5, 2) ?: refuse(field, "a non-numeric month")
        val day = digits(value, 8, 2) ?: refuse(field, "a non-numeric day")
        if (!validDay(year, month, day)) refuse(field, "an invalid calendar date")
        frames.add("TDAT" to (value.substring(8, 10) + value.substring(5, 7)))
        if (value.length == 10) return frames
        if (value[10] != 'T' || value[13] != ':') refuse(field, "a time outside THH:mm")
        val hour = digits(value, 11, 2) ?: refuse(field, "a non-numeric hour")
        val minute = digits(value, 14, 2) ?: refuse(field, "a non-numeric minute")
        if (hour !in 0..23 || minute !in 0..59) refuse(field, "an invalid recording time")
        frames.add("TIME" to (value.substring(11, 13) + value.substring(14, 16)))
        return frames
    }

    /** Explicit primary-date edits replace its legacy components, including orphans. */
    fun withoutPrimaryEntries(raw: RawTags): RawTags = RawTags(raw.blocks.map { block ->
        if (!isId3(block.format)) block else RawTagBlock(block.format, block.offset, block.size,
            block.entries.filterNot { entry ->
                isAuxiliary(entry.key) || TagMapping.fieldFor(block.format, entry) == TagField.DATE
            })
    })

    fun cannotConvert(key: String): Nothing = throw UnsupportedFeatureException(
        "converting legacy ID3 DATE '$key' without losing information",
        "the '$key' value has no unambiguous timestamp equivalent; retain ID3v2.3 " +
            "or explicitly replace or remove DATE",
    )

    private fun refuse(field: TagField, reason: String): Nothing = throw UnsupportedFeatureException(
        "ID3v2.3 ${field.name}: $reason",
        "ID3v2.3 ${field.name} cannot represent $reason; write ID3v2.4 to retain date precision. " +
            "ID3v2.3 uses four-digit year fields " +
            "and has no lossless native representation for this value",
    )

    private fun validDay(year: Int, month: Int, day: Int): Boolean {
        val maximum = when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            1, 3, 5, 7, 8, 10, 12 -> 31
            else -> return false
        }
        return day in 1..maximum
    }

    private fun digits(value: String, start: Int, length: Int): Int? {
        if (start + length > value.length) return null
        var result = 0
        for (index in start until start + length) {
            val digit = value[index]
            if (digit !in '0'..'9') return null
            result = result * 10 + (digit - '0')
        }
        return result
    }

    /** Constant state avoids collecting or pairing arbitrary source value lists. */
    private class Component {
        var present = false
            private set
        private var value: String? = null
        private var ambiguous = false

        fun add(text: String?, opaque: Boolean) {
            present = true
            if (text == null || opaque) {
                ambiguous = true
                return
            }
            val next = text.trim()
            if (value != null && value != next) ambiguous = true
            value = next
        }

        fun single(): String? = if (ambiguous) null else value
    }
}
