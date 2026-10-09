## Channel mixing

The downmix coefficients come from the Recommendation; no implementation was
read.

| Field | Value |
|---|---|
| Specification | Recommendation ITU-R BS.775-3 (08/2012), Annex 4, Table 2, `R-REC-BS.775-3-201208-S`, SHA-256 `61d3baba8cab389e69cd41c86e97054d82127281af5e1f5b5edeb7633ac8e3c1` |
| Never opened | ffmpeg's source, whose mixing for positions BS.775 does not cover was measured by running it |

BS.775 covers 3/2 material without a low-frequency channel. The rules for
the side, back centre, front centre pair and top front positions were
measured from ffmpeg's float mixing with a different tone in every input
channel, and the oracle suite holds AudioKodec to them.
