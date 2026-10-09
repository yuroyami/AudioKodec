package io.github.yuroyami.audiokodec

/**
 * MP4 metadata assembled box by box from Apple's description of the item
 * list, rather than written by AudioKodec, so the reader is checked against
 * the format and not only against the library's own writer.
 */
internal object Mp4TagFixtures {

    val png: ByteArray = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)
    val jpeg: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 4, 5, 6)

    /** An item the reader cannot follow: a `rtng` atom holding a box that is not `data`. */
    val opaqueItemBody: ByteArray = box("junk", byteArrayOf(1, 2, 3))

    /** The iTunes gapless value, which AudioKodec keeps without modelling it. */
    const val SMPB: String = " 00000000 00000840 000001C0 0000000000000BB8 00000000"

    /** A `udta` box holding an item list with every kind of value the format has. */
    fun udta(chapters: ByteArray = ByteArray(0)): ByteArray = box(
        "udta",
        chapters,
        fullBox(
            "meta",
            box("hdlr", ByteArray(8), ascii("mdirappl"), ByteArray(9)),
            box(
                "ilst",
                item("©nam", data(1, utf8("Tïtle 日本"))),
                item("©ART", data(1, utf8("One")), data(1, utf8("Two"))),
                item("©day", data(1, utf8("2021-05-04"))),
                item("trkn", data(0, byteArrayOf(0, 0, 0, 3, 0, 12, 0, 0))),
                item("disk", data(0, byteArrayOf(0, 0, 0, 1, 0, 2))),
                item("gnre", data(0, byteArrayOf(0, 18))),
                item("tmpo", data(21, byteArrayOf(0, 120))),
                item("cpil", data(21, byteArrayOf(1))),
                item("ldes", data(2, utf16("Long ünïcode"))),
                item("covr", data(14, png), data(13, jpeg)),
                freeform("MusicBrainz Track Id", data(1, utf8("rec-id"))),
                freeform("replaygain_track_gain", data(1, utf8("-6.50 dB"))),
                freeform("iTunSMPB", data(1, utf8(SMPB))),
                item("xid ", data(1, utf8("Label:isrc:XX0000000001"))),
                item("stik", data(21, byteArrayOf(1))),
                box("rtng", opaqueItemBody),
            ),
            box("free", ByteArray(32)),
        ),
    )

    /** A `udta` box whose item list holds only an iTunes gapless record. */
    fun gaplessUdta(record: String): ByteArray = box(
        "udta",
        fullBox(
            "meta",
            box("hdlr", ByteArray(8), ascii("mdirappl"), ByteArray(9)),
            box("ilst", freeform("iTunSMPB", data(1, utf8(record)))),
        ),
    )

    /** The `iTunSMPB` text for a delay, padding and frame count. */
    fun smpb(delay: Int, padding: Int, frames: Long): String =
        " 00000000 ${hex(delay.toLong(), 8)} ${hex(padding.toLong(), 8)} ${hex(frames, 16)} 00000000 00000000 00000000"

    private fun hex(value: Long, digits: Int): String = value.toString(16).uppercase().padStart(digits, '0')

    /** A small QuickTime file with the item list above and its index first. */
    fun tagged(test: Mp4Test = Mp4Test(), moovFirst: Boolean = true, frames: Int = 600): ByteArray =
        test.build(frames = frames, channels = 2, chunkFrames = 128, moovFirst = moovFirst, moovExtra = udta())

    fun item(type: String, vararg data: ByteArray): ByteArray = box(type, *data)

    fun freeform(name: String, vararg data: ByteArray): ByteArray =
        box("----", fullBox("mean", ascii("com.apple.iTunes")), fullBox("name", utf8(name)), *data)

    fun data(type: Int, value: ByteArray): ByteArray =
        box("data", byteArrayOf(0, (type shr 16).toByte(), (type shr 8).toByte(), type.toByte()), ByteArray(4), value)

    fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        val name = ByteArray(4) { (type[it].code and 0xFF).toByte() }
        return u32(body.size + 8) + name + body
    }

    fun fullBox(type: String, vararg parts: ByteArray): ByteArray = box(type, ByteArray(4), *parts)

    fun ascii(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

    fun utf8(text: String): ByteArray = text.encodeToByteArray()

    private fun utf16(text: String): ByteArray = ByteArray(text.length * 2) { k ->
        val unit = text[k / 2].code
        (if (k % 2 == 0) unit shr 8 else unit).toByte()
    }

    private fun u32(value: Int): ByteArray =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    /** Where [type] first occurs as a box name in [bytes], or -1. */
    fun find(bytes: ByteArray, type: String, from: Int = 0): Int {
        val name = ByteArray(4) { (type[it].code and 0xFF).toByte() }
        for (at in from..bytes.size - 4) {
            if ((0..3).all { bytes[at + it] == name[it] }) return at
        }
        return -1
    }
}
