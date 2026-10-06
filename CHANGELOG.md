# Changelog

## 0.1.0 (unreleased)

First release. The uncompressed family end to end, plus FLAC, Vorbis, MPEG
audio and Ogg.

### Added

- Read and write WAV, including WAVE_FORMAT_EXTENSIBLE, RF64 and BW64.
- Read and write AIFF and AIFF-C, and Sun AU.
- Read Wave64.
- Decode integer PCM at 8, 16, 24 and 32 bits in both byte orders, IEEE float
  at 32 and 64 bits, A-law, mu-law, IMA ADPCM in the WAV and QuickTime block
  layouts, and Microsoft ADPCM.
- Encode all of those except QuickTime IMA.
- Float32 PCM model with an exact integer view for lossless codings, sample
  format conversion, TPDF dither, interleaving and channel remapping.
- Pull-based streaming decoder with sample-accurate seeking.
- Header-only probing.
- A sniffer that names 29 audio formats.
- Guards for truncated, corrupt and hostile input, with a typed exception
  hierarchy and an allocation ceiling.
- Tag reading on any file, decodable or not: ID3v2.2, 2.3 and 2.4, ID3v1 and
  v1.1, APEv1 and APEv2, Lyrics3v2, RIFF `LIST INFO`, Broadcast Wave `bext`,
  AIFF text chunks, and an ID3v2 chunk inside a RIFF or AIFF file.
- A merged tag view with format precedence, alongside every block exactly as
  it was stored.
- Tag writing for ID3v2.3 and 2.4, RIFF `LIST INFO` and AIFF text chunks, with
  the audio payload copied byte for byte.
- Pictures, chapters, numbered genres, ReplayGain and the Opus R128
  convention.
- FLAC, decoded bit-exactly at 8 to 32 bits and encoded at 8, 16 or 24, with
  sample-accurate seeking with or without a seek table, and verification against
  the MD5 the encoding program stored.
- Apple Core Audio Format, read, including packet-table priming and remainder
  trimming, and named channel layouts reordered into AudioKodec's channel
  order.
- The Ogg container, read in full: pages and checksums, packet reassembly
  across pages, several logical streams at once, chained streams and granule
  positions. Vorbis, Opus and FLAC inside Ogg all decode and seek; Speex has
  its headers, length and comments read.
- MP4, M4A, M4B, 3GP and QuickTime, read: the box tree, sample tables, a
  `moov` before or after the audio, fragmented files, edit lists, and
  chapters from a chapter track or a Nero `chpl` list. ALAC, Opus, FLAC and
  uncompressed audio inside decode and seek; AAC, MP3, AC-3 and AMR are named
  with their length and refused.
- Apple Lossless, decoded from MP4 and CAF at 16, 20, 24 and 32 bits with one
  to eight channels, exactly, with integers through `IntPcmAccess`.
- Opus, decoded: SILK, CELT and the hybrid of the two, the redundant frames
  that smooth a change of codec, concealment of lost packets and recovery from
  the redundant copy the next packet carries, and every channel mapping,
  surround in AudioKodec's channel order and ambisonics with or without a
  demixing matrix. Pre-skip, end trim and the header's output gain are
  applied, and seeking runs 80 ms ahead of its target. Identical to libopus,
  sample for sample, and passes the RFC 8251 conformance vectors.
- Vorbis, decoded: floor type 1, residue types 0, 1 and 2, channel coupling,
  block switching, and up to eight channels reordered into AudioKodec's channel
  order. Checked against libvorbis sample for sample at every quality level.
  Floor type 0 is parsed and refused, because no encoder has emitted it since
  the format's 2001 beta releases.
- An inverse MDCT and a complex FFT in `commonMain`, checked against the
  transforms' own definitions rather than only against another decoder.
- Ogg comment headers, written, by rebuilding the header pages and renumbering
  the pages after them with the audio packets left untouched.
- Vorbis comments and FLAC picture blocks, read and written, with seek tables,
  cue sheets and application blocks carried across a tag edit untouched.
- The MPEG audio framing layer: frame headers for MPEG-1, 2 and 2.5 in all
  three layers, sync words confirmed against the frames that follow them,
  `Xing`, `Info` and `VBRI` headers, LAME gapless trimming, and exact
  durations for constant and variable bitrate files alike. Layers I, II and
  III all decode, with sample-accurate seeking.
- `audiokodec-io`, the optional kotlinx-io bridge.
