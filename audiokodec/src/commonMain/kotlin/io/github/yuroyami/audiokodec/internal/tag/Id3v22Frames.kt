package io.github.yuroyami.audiokodec.internal.tag

/** Four-character counterparts from the published ID3 frame catalogues. */
internal object Id3v22Frames {
    // CRM encrypts a set of old frames and has no direct counterpart. Unknown
    // three-character extensions likewise cannot be assigned a new identity.
    private val identifiers = mapOf(
        "BUF" to "RBUF", "CNT" to "PCNT", "COM" to "COMM", "CRA" to "AENC",
        "ETC" to "ETCO", "EQU" to "EQUA", "GEO" to "GEOB", "IPL" to "IPLS",
        "LNK" to "LINK", "MCI" to "MCDI", "MLL" to "MLLT", "PIC" to "APIC",
        "POP" to "POPM", "REV" to "RVRB", "RVA" to "RVAD", "SLT" to "SYLT",
        "STC" to "SYTC", "TAL" to "TALB", "TBP" to "TBPM", "TCM" to "TCOM",
        "TCO" to "TCON", "TCR" to "TCOP", "TDA" to "TDAT", "TDY" to "TDLY",
        "TEN" to "TENC", "TFT" to "TFLT", "TIM" to "TIME", "TKE" to "TKEY",
        "TLA" to "TLAN", "TLE" to "TLEN", "TMT" to "TMED", "TOA" to "TOPE",
        "TOF" to "TOFN", "TOL" to "TOLY", "TOR" to "TORY", "TOT" to "TOAL",
        "TP1" to "TPE1", "TP2" to "TPE2", "TP3" to "TPE3", "TP4" to "TPE4",
        "TPA" to "TPOS", "TPB" to "TPUB", "TRC" to "TSRC", "TRD" to "TRDA",
        "TRK" to "TRCK", "TSI" to "TSIZ", "TSS" to "TSSE", "TT1" to "TIT1",
        "TT2" to "TIT2", "TT3" to "TIT3", "TXT" to "TEXT", "TXX" to "TXXX",
        "TYE" to "TYER", "UFI" to "UFID", "ULT" to "USLT", "WAF" to "WOAF",
        "WAR" to "WOAR", "WAS" to "WOAS", "WCM" to "WCOM", "WCP" to "WCOP",
        "WPB" to "WPUB", "WXX" to "WXXX", "TCP" to "TCMP",
    )

    fun identifier(key: String, major: Int): String? =
        if (key == "IPL" && major == 4) "TIPL" else identifiers[key]
}
