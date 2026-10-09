package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.ac3.Ac3Frame
import io.github.yuroyami.audiokodec.internal.tag.Base64

/**
 * Three syncframes each of AC-3 streams ffmpeg's encoder wrote, with the
 * dither flags cleared and the two CRCs repaired so that a decode is the same
 * on every target, and what ffmpeg's decoder gives for them.
 *
 * Recipe: `ffmpeg -f lavfi -i aevalsrc=<tones, a sweep and clicks> -c:a ac3
 * -b:a <rate>k [-channel_coupling 1]`, cut at the third syncframe, with every
 * dither flag cleared and crc1 and crc2 recomputed (the JVM oracle suite does
 * this to every file it compares), then decoded by ffmpeg to 32-bit float.
 * The digest holds every sixteenth frame of that decode as 16-bit integers
 * in steps of 1/8192, channel by channel, and the RMS values the root mean square of each
 * channel of each syncframe.
 */
internal object Ac3Fixtures {

    class Fixture(
        val name: String,
        val bytes: ByteArray,
        val digest: ShortArray,
        val rms: FloatArray,
        val rate: Int,
        val channels: Int,
        val layout: String,
    ) {
        /** The syncframes, one array each. */
        fun frames(): List<ByteArray> {
            val list = ArrayList<ByteArray>()
            var at = 0
            while (at + Ac3Frame.HEADER_BYTES <= bytes.size) {
                val size = Ac3Frame.parse(bytes, at)!!.frameBytes
                list.add(bytes.copyOfRange(at, at + size))
                at += size
            }
            return list
        }
    }

    private fun decode(base64: String): ByteArray = Base64.decodeOrNull(base64) ?: error("a fixture is not base64")

    private fun shorts(base64: String): ShortArray {
        val raw = decode(base64)
        return ShortArray(raw.size / 2) { ((raw[2 * it].toInt() and 0xFF) or (raw[2 * it + 1].toInt() shl 8)).toShort() }
    }

    /** Mono, 32000 Hz. */
    val MONO: Fixture = Fixture(
        "mono",
        decode(
        "C3c3doBAL4QLAKLCrw3z4y/oBBHu7ogWuv/czFOkcAAjNCAngP63MwXQ/voqReCwWS8fRMJ3sNDEdZDQxpVw0BgOE9V31d8BrFvp" +
        "/iO8rISyLWFIEH+fhfM4rq35F7keLUlDzNCUS40LWDFYSDJYSDJUAgaBC2seP1shV3z98+fPn6p8+fPnz58Ax4uyXIUtoye694Mg" +
        "A9oRFu7rcbKZZDLlgAx3fSLDkTqsrNSfyAAxpAd68jyILy1FnlgAAHy8C3cSrIBAL4QKAK1w6Q1VKtXVOqNsy/nhBFs8dr3LXMhv" +
        "3lDBWV5XXWK8E+pSi2M2ztpwMDbjk3szMyXrusDt3IPYwA/G3Vqh8yFZy1/6uiOD5TsUMbVNy7wsPec7PfiMoJPHZWmmKVplFCp9" +
        "KwEDQIWQM74/cKwqfV3z98+dKaamu+fPgHcBw3vDhgodchQcF0gA0ft2D3VsYZh225uMAAHfDx/XITZIaJBt/40gAz/dpG2heXbw" +
        "AFq+kkAAALHQC3fU+oBAL4QLAR2xuPob4y/nrB9tQ0D+hQShSkuAEDGgIEAeXDEwO8UzK1ujMqxoLNqiFNoiVMGAoy8p2LdADpZW" +
        "fOFRxl1Ue/1B7yVeQ8Pr95m7xwKC/2qinrviLnGs+DDTTRsskwqQ0Uw6tAgcACydI7F861FNd+p2Kaammppqab58A+odtte3NBjy" +
        "9VKQgVAAMS4T0sPEftFe0fkeCUADMiHR0dG8EPnn30pxWAAkrdIDEgQfcIGIDCFLAENP",
        ),
        shorts(
        "AAABAAIAAgAMAAUA+v8NABAA+P/6/+n/x//o/7b/5/9mDPcfRxZnB3oBIwkjBi75z/iKAz0GQf9y+zz+uQF1A7cByPux+hEEjwj/" +
        "/N70cgGLDGcAc/Oy/WULkQMt9+D7JQX9Awr/A/2b/GABagdzAJP1yf0DDKUDD/SP+x0KdQR1+Qj8zgEsA1EDzf3b94IBigvt/lHz" +
        "jgCACxIAG/ii/sMCfgOHAgD6QPlFCMMI3vaV97YHYQYv/FH83/0EAhkIF/4i9JoCKww9/ST3zQCOA38D9gBc9zj9KwwLAhv00v4F" +
        "B/QBXP/O+rYHLx01GfUHhAj2B30COv+A+5L6wgimB2r1NPuBBzUDZgAh+0v57ghxB6n1nPs1BQ8DCgOH+Yf4iAqwBUv3IP39AM4E" +
        "twUE9kT6zQpyAgL8Rvxi/MIJdQTh8wT/iQUPA6sB7PUz/nkMw/4W+ov93f/GChL+bvTAA8kE9QLi/Sf1VwbGCPH6lfyg+wIG3Ai+" +
        "9f77twMTBLMEC/Xc/C0KigBM/5j3yv91DJL8nfoY/XoCCAtm+J35/gKGA8UGz/V2+/8HRwKxAYv1MAwBHv8ROxHXAxcFowmv+av8" +
        "c/ubCDYFHfao/pT//glc/3f1NQKKAoMIrfib+N4FeAPDBDv0Gf8JBwUDZ//i830G8AT2AaL5+PijCk4BZwCI9fABHQkT/+v8i/b/" +
        "CVoDzv42+CD+SQuE/gz+DPYxCDAFdv47+Tj8jwtM/4f+dfVmB08F3v+b+P77egqTACz/",
        ),
        floatArrayOf(0.224050f, 0.239911f, 0.244746f),
        32000, 1, "mono",
    )

    /** Stereo, coupled and rematrixed, 44100 Hz. */
    val STEREO: Fixture = Fixture(
        "stereo",
        decode(
        "C3e3k0xAQ+EA8IkICAgjA9O0fnYTPiiXH1cYb2hwzL+mcOHIAyfdxzmC794xZUKeosVx1OwKpcRuBgRSi12vXy3fGGQQNC/3gH/H" +
        "GCVz8y4H2SLZP2KuHqigmlaXeejRDoojze1X3kUYra8k4oWJKPgoDwk6hdmpxHKSI+ezUhOycKzV9ZyWmCnX0HrZ2FpbYJfiZ2aq" +
        "APUr03ymIcN8YFNLMfyxEU01OxTTU01QDhhBcMFZNnz6JYBeXyMttTODJX1pq0wxujw+dLZQ7CNO/Ett4hQ1Sd7wjUteQHSbJ0Ie" +
        "k3Pn6JIYlecVJ9Dg0uTbkFhn1GWV4Ulyc2tHu+XJZIEV19DhwbmzSob5/QEZi+edj99UU03z58+pgHgmaQQnEHrp3j3fUkJCmFh/" +
        "5ieT9VnfupYBEsxNFwVXfPnz58CIFoO9qJYjMMFF8Is+AyHq+JxLjyPsKRxsAQlKi+ERMVwABlyGaA9m8xQHUI1KV/9SIlpGGhAC" +
        "biERKsAQ1ODqGackAAXCbeHW3hd6g1l71w3gGyCChBPSB9UwPCbcIlgLd5ySTUBD4QDwiXS0dSEB+fx0j+k/gB8+erD98+DWF3ZJ" +
        "dTIrjL+gMGDIAE+giB5AX3vUAooEdhmZmZmASE3Jq4dnfZsu9bDBIUD8TuNiS7zou3uLz9saPf8ACMgo3g5+45swQoEEAPpD3gAw" +
        "R0megAQ0BO8DULD8fnj5iwHpyM1r7fEd6I4V/BMY5wV3S0bKTD+DGkgszYEVb8wnbeAS68oB+LOKssu6RMXaT+BWCtZpgEPz8/ul" +
        "3djlK79Sa3E6YFVNtceAMovfzrTfP3z58+pvnwAcJ//l92BI37RghpkyWEBQvvv0vm3oEkpRimAfZSd774IMfemvLjDL2rhuq0DB" +
        "gJyrgZB86V4jVx/CCZIAwOu+zgXpHwDAencUAB8f/GTNLAwJReG3W6xPfVLySfqRXUOWTxL4eO5ayvMFbulScPJVSIe4g2JbIRVW" +
        "UaW06ChsSAiChosODc3qiob58+fPnBSvnyw+rvgHn+c8B51bgRvj6EHgC8cyAdfpra52nkdwBAAA8BnH+OQYcHeoUgSCF4xl8G0h" +
        "GoBYSWPQAJmsC3eu9U1AQ+EA8IkMjM0hKQn1bVXfPqAiXPf552KeFN8+fP1NMoAY+fK8N8/Mv6GzZsgEQNG/xHYQNvRVAooMAAP/" +
        "x8znWfJPDjAm/zu8ARPTk24Kfvnz58+A11M4NtBx9Y6NXFE9AAAB8Aj4h5uQDs/kUDDvAITExLC4oI5ABYS2Hr14YxI+tHL76gQA" +
        "B/42/i0/u6TQXqAzvALy83ND8/P4yOvSplcJ14UK3CjAC9EvjQvsJ43YiP4SIkPQpYgPhJXrrSVFB8aE2zUhA/tXavdjbqf2Kk4G" +
        "2mM6lHy2W8EWsCL2CqJjZQaXkFoh7RmyXrEGloAwS7WSg/NPbx/bQwGY/+0fWdnS02thmdFiolSfJnxQn6P2K3K0tMxSvdsezK2k" +
        "eE3mM5srJ3J3Qg+n1jIuy7tB9H2kXgIfkB/dHQ7n5269In/WolHBfPnwAsREw/a2CADmgYP1v4xAuUkmABJHqZUGhEpwpW0/r10E" +
        "guWpkBdSPo0K2ER7ymECU3LTN7y3/4AAAA8QB5iE6tp4Gn0AEEAgAAAAAADxwAbbtSA4LQ==",
        ),
        shorts(
        "AAAAAAAAAAABAAAA/////wMAAQAGAAAA8v8BAP7/AQAIAP//+v8BABkA+////wUAz//2/w0ACQDx//T/jv8NAMEMmgBLHlMMKR6w" +
        "BNoSy/ZTCB75qwkdBb8A4wVPCpH9vAjy/D//twJt99MAifjB+p8A9v67BhIIpAXpAykA5vb3+/L34ftXB4n+dAsfAZj81QJ984ID" +
        "+f7eAegKqv01BAX6T/iC++r6kwKPBLIIzQNNBhj9Tvxu/vX0WgMN+d//PgWN+ogMNABwBvMHNvm4AVPzb/YQ++j6jQd3CUgLtggv" +
        "A5j4APmV9FP37QMh/ngM2ATt/3AFW/TbAc78wP5yCUf9IgVw/Er5Of2L+msBQgSYBoIEKgZG/ff9Bv369XsCU/iNARsEnvxnDDD/" +
        "+AbvBIT5JwF783H5YPtx/XgHpAcwCsgEMgI5+Aj6mfis+dsGfP7gCQoCDfv8AlD0gAM+Ar4CoAwN/tkAdPhn8/D5KvzQA5ELZgt0" +
        "BkAGOfb9+I33U/OIB1D8pAnnCPP6YArk9ccAoQKz+OUJ3Plk/5D/2PacAuv+FgMQCMUDXQJgAjH5UvwV/S/3ywWr+18DXAdU+/IL" +
        "cfwfApUDbPVIA371Jv1bARn9uwmsAiEGtgIe/qX9RPvn/bj8yhXD/QIWs/+rEBMFehD5B0IV+wDYFhr2Kv7s9YX9kALrAWMM8AIA" +
        "B/D9hfqp/MH2vAEm/XEDJAOG/pIDjPwQAw0BPAO3A4z+ev9V9y/8FPnt//EE0AOYDJ8AhART/Ff32P6T9WED3v+nAbwGGf2mBOD9" +
        "iAFCAiQApAKx/Gj+G/n0/KIR6AC5GzUDjh7+/+QStPyHB/7+1wpZA0UEAAKAB8P8AQP7/Mv/+AJw/hYExvpq/Xz50/q6AR8CgAs1" +
        "Bu4Gef60+Mf4YfTQAFX+TQgCB9D/SwXP9kIBh/8+AOAJp/xYAdD3X/Wk/N/9EAleC0ELqAJm/jv0svTX/DD67AuLA7sDzAT98wED" +
        "0ftmAwAMPv9hBMv2UfRL+Jj73wUGC5kMbQSvAs71xve8+435HAme/+ED9AB7+FUDZfyaB6sFugJ2A3T2SPxV9bz87gKHASQLMwNF" +
        "BNEAoPyk/Jn84Pwc/LUDGftUBZMCvPt0C674YAUbBfr21wi19fn5xQAo9vEF5gZ0A1EKTANV+HoB7PWM+A4I7fU/CYoCHfhyDAH4" +
        "3QQTB0j6fwau+nH6c/0G+2b9TwMtA8IDpgqB/+MD6Pyy9W/9XPYfA5oCiAWDBhz8CgMD+BIDPwUbAI8JxPYt+aL3//UgBicIzAtg" +
        "CWwBKvdS+iD4WPxxCBP8CQYs/VX5JAek+xUL0gNQ/lgDVfS7/337tfxuA/789wLMBOAECga/BRj5K/ta+Hvz5Rwn/jEaywlbCLwF" +
        "7A2O/wUeSP9jFYX6TfYn9+f9ggLwBc0M5QEbBOT9Rfls/AT75/5R/awGUP2kArwFD/ZqC539Rf9PDHX0fAAp+17zkwKiAXgCewr6" +
        "BYv9wgYF+Uz6FAF7838Dgv90AlEIP/7iAy35",
        ),
        floatArrayOf(0.239418f, 0.193783f, 0.249962f, 0.217045f, 0.207638f, 0.258113f),
        44100, 2, "stereo, coupled and rematrixed",
    )

    /** 5.1, coupled, 48000 Hz. */
    val SURROUND: Fixture = Fixture(
        "surround",
        decode(
        "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",
        ),
        shorts(
        "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" +
        "9f+PDPET",
        ),
        floatArrayOf(0.241030f, 0.187924f, 0.208457f, 0.173590f, 0.239347f, 0.187629f, 0.252853f, 0.215095f, 0.243186f, 0.049919f, 0.250301f, 0.252610f, 0.207140f, 0.273415f, 0.275066f, 0.145130f, 0.207636f, 0.219922f),
        48000, 6, "5.1, coupled",
    )

    /** The mono stream as ffmpeg wrote it, four syncframes with the dither flags set. */
    val MONO_DITHERED: ByteArray = decode(
        "C3drcoBAL4QrAKLCrw3z4y/oBBHu7ogWuv/czFOkcAAjNCAngP63MwXQ/voqReCwWS8fRMJ3sNDEdZDQxpVw0JgOE9V31d8BrFvp" +
        "/iO8rISyLWFIEH+fhfM4rq35F7keLUlDzNCUS40LWDFYSDJYSDJUIgaBC2seP1shV3z98+fPn6p8+fPnz58Ax4uyXIUtoye694Mo" +
        "A9oRFu7rcbKZZDLloAx3fSLDkTqsrNSfyIAxpAd68jyILy1FnlgAAHm0C3cExoBAL4QqAK1w6Q1VKtXVOqNsy/nhBFs8dr3LXMhv" +
        "3lDBWV5XXWK8E+pSi2M2ztpwMDbjk3szMyXrusDt3IPY0A/G3Vqh8yFZy1/6uiOD5TsUMbVNy7wsPec7PfiMoJPHZWmmKVplFCp9" +
        "KxEDQIWQM74/cKwqfV3z98+dKaamu+fPgHcBw3vDhgodchQcF0oA0ft2D3VsYZh225uMBAHfDx/XITZIaJBt/40oAz/dpG2heXbw" +
        "AFq+kkAAAI8cC3fhKYBAL4QrAR2xuPob4y/nrB9tQ0D+hQShSkuAEDGgIEAeXDEwO8UzK1ujMqxoLNqiFNoiVMmAoy8p2LdADpZW" +
        "fOFRxl1Ue/1B7yVeQ8Pr95m7xwKC/2qinrviLnGs+DDTTRsskwqQ0Uw6tIgcACydI7F861FNd+p2Kaammppqab58A+odtte3NBjy" +
        "9VKQgVCAMS4T0sPEftFe0fkeCUgDMiHR0dG8EPnn30pxWIAkrdIDEgQfcIGIDCFLAPvGC3et/4BAL4QrAYIx6vob4y/oDAnAP4fH" +
        "4fX8yCgMd8gXZCNm9QJ98/QMwQflPk0lzeG6YTVaSNNN5oAzaIBTWIpAGrLk1bqjbWINUdOArdCr3/ou9ALaBOMHypG7nQ9pNMp+" +
        "LHreB5pVV7LHPxtPCuQDynTo1vbmdFr9gstAAv30IXjBkF0w4QR8AXnXrwNQJx4wduoD41TuyHzTDXiB80zlIZopoi94UM0XviMp" +
        "Af86vnz58+fPnz58AgCAALqP",
    )

    /** Two frames of Enhanced AC-3, which shares AC-3's sync word. */
    val ENHANCED: ByteArray = decode(
        "C3cAfzKHwAAhBeAg471Xrw3z98+fBpX5397AqxfmH3U2H/DfhohA0vbvdRimQ3FtBsvGrz1EKLI3ZcdtlmkJax7AxSNXATHW+ljx" +
        "hy6YwU9WzJ0jevj77JGNt9Ewsx20iSzfWHp+Voak6XhaHvAr+RG7sxt7QbO0Ev1RL/YKv2Cn9wpBgKP4EKMk8fyI/hOnz59Dava7" +
        "rU9hvnu9+lpUqy3W/ROYaF+NhVntcDtjQ3oumB31IgJVkQbJSRAdhwfW1zecEk9eQ7kMiADoye47ZpZ7SZAJRbQyyCkfdlf0Wvkf" +
        "mNE3+VX8bJyRI8JymuMhjBnHd0aMMspgAAAAAADeoQt3AH8yh8AAAAbUISaJ4/kiQpfPnSaE+ew32dKl1vqVNTSpVa6qk7SwnT5d" +
        "ShPYgQX4PoAtxZqKwjmApw0koV4jSKC9QRwrscKoqAiaeFPh0dlMIuxwWxbqwqZX5quPTi5+b4PesdZUKsuYxuUclxiDINTbBOsf" +
        "Wp5pbyTP5OhrQMewdr21BthR8LNQanMjk+HBdCJvz9DOPpCSMIFqFeBSmgw5gy0lUTOKEsUYJjiMyw4eVDcxWpug6ip7QtX+ek24" +
        "2M3SUWpsdbTFamRQ4rLCxoWKKxxzRl5LGo6+KGVb/ck5cJzziJc10qBLokWslUxEjIj3iIAAAAAAAAAAm3Y=",
    )

    val ALL: List<Fixture> get() = listOf(MONO, STEREO, SURROUND)
}
