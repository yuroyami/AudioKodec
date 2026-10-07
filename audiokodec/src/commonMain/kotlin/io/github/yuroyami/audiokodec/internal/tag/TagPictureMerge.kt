package io.github.yuroyami.audiokodec.internal.tag

import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.tag.TagPicture

/** Metadata actually represented by a source: role=1, MIME=2, caption=4. */
internal enum class PictureMetadata(val mask: Int, val richness: Int) {
    RICH(7, 3),
    LEGACY_COVER(3, 2),
    MP4_COVER(2, 1),
    // A declared role takes priority over MP4's unspecified cover role.
    APE(1, 2),
}

/**
 * An image set, retaining the richer representation of equivalent copies.
 *
 * Each source profile has indices for its intersections with the other
 * profiles. Only properties both sources represent participate in a match.
 * Comparing the image bytes themselves avoids hash-collision ambiguity and
 * bounds membership comparisons with an AVL tree. Dimensions describe the
 * opaque image, rather than identifying a second copy of the same image.
 */
internal class TagPictureMerge(private val options: DecodeOptions = DecodeOptions.DEFAULT) {
    private class Stored(val position: Int, var picture: TagPicture)
    private val indices = LinkedHashMap<PictureMetadata, Map<Int, TagValueIndex<Stored>>>()
    val pictures: MutableList<TagPicture> = ArrayList()
    private var nodes = 0L

    fun add(picture: TagPicture, metadata: PictureMetadata = PictureMetadata.RICH) {
        val probe = Stored(-1, picture)
        for ((source, sourceIndices) in indices) {
            val found = sourceIndices.getValue(source.mask and metadata.mask).find(probe) ?: continue
            val first = found.picture
            val width = first.width.takeUnless { it == 0 } ?: picture.width
            val height = first.height.takeUnless { it == 0 } ?: picture.height
            val depth = first.colourDepth.takeUnless { it == 0 } ?: picture.colourDepth
            val colours = first.colourCount.takeUnless { it == 0 } ?: picture.colourCount
            if (width != first.width || height != first.height || depth != first.colourDepth || colours != first.colourCount) {
                val enriched = TagPicture(
                    first.type, first.mimeType, first.description, first.bytes,
                    width, height, depth, colours,
                )
                found.picture = enriched
                pictures[found.position] = enriched
            }
            return
        }
        val sourceIndices = indices.getOrPut(metadata) {
            PictureMetadata.entries.map { it.mask and metadata.mask }.distinct().associateWith { mask ->
                TagValueIndex(comparator(mask)) {
                    options.checkAllocation((nodes + 1) * 96L, "remembering merged artwork identities")
                    nodes++
                }
            }
        }
        val stored = Stored(pictures.size, picture)
        for (index in sourceIndices.values) index.add(stored)
        pictures.add(picture)
    }

    private fun comparator(mask: Int): Comparator<Stored> = Comparator { left, right ->
        comparePictures(left.picture, right.picture, mask)
    }

    private fun comparePictures(left: TagPicture, right: TagPicture, mask: Int): Int {
        if (mask and 1 != 0) {
            val type = left.type.ordinal.compareTo(right.type.ordinal)
            if (type != 0) return type
        }
        if (mask and 2 != 0) {
            val mime = left.mimeType.compareTo(right.mimeType, ignoreCase = true)
            if (mime != 0) return mime
        }
        if (mask and 4 != 0) {
            val caption = left.description.compareTo(right.description)
            if (caption != 0) return caption
        }
        val size = left.bytes.size.compareTo(right.bytes.size)
        if (size != 0) return size
        for (at in left.bytes.indices) {
            val byte = (left.bytes[at].toInt() and 0xFF).compareTo(right.bytes[at].toInt() and 0xFF)
            if (byte != 0) return byte
        }
        return 0
    }
}
