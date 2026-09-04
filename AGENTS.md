# Notes for anyone working in this repository

Read [CONTRIBUTING.md](CONTRIBUTING.md) first. It has the rules. This file has
the traps: things that cost somebody real time already, written down so the
next person does not pay for them twice.

## Gotchas

- Multichannel decoders must reorder channels into the canonical order before
  emitting a frame. Vorbis, Opus and AAC each use their own speaker order, and
  getting it wrong is silent: the audio plays, from the wrong speakers. Test it
  with a 5.1 signal carrying a different tone per channel. See #4, #13, #42.
- Keep the arithmetic mode of whatever reference a codec was ported from. If
  the reference is fixed point, stay fixed point; if it is float, stay float.
  Switching makes every conformance comparison approximate instead of exact.
  See #19.
- Returning the low complexity core of an AAC file that uses spectral band
  replication is wrong, not partial. The result sounds muffled and half rate,
  and a caller cannot tell from the samples that anything is missing. Refuse
  with the feature named instead. See #13.
- Tag writers corrupt files in four specific places: ID3v2.4 unsynchronisation,
  the byte order mark on UTF-16 text, padding that must survive a rewrite, and
  MP4 atom sizes that must be recomputed up the tree. Each one gets its own
  regression case. See #8.
- A single wrong digit in one of the MPEG constant tables is silent. The audio
  still decodes, slightly wrong. Agree every table across several independent
  sources before using it: doing that caught three defects in widely copied
  versions of these tables. `Mp3TablesTest.kt` checks the properties the tables
  must have rather than the digits themselves, which is the only check that
  scales.
- The boundary between the Huffman regions of a granule that switched windows
  is not always 36. That value is right for MPEG-1 and wrong for MPEG-2, where
  a start or stop block needs 54 and only a short block needs 36. Getting it
  wrong shows up nowhere except on the few frames either side of a transient.
  See `internal/mpeg/Mp3Granule.kt` around line 300.

## Working rules for agents

- Stay on the branch you are on. Do not create a new one without asking.
- Do not add a `Co-Authored-By` trailer to commits.
- No em dashes anywhere: source, comments, documentation, commit messages.
  Rewrite the sentence rather than substituting another symbol.
- Spelling is British throughout the repository.
