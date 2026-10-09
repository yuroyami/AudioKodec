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
  positions. Vorbis, Opus, FLAC and Speex inside Ogg all decode and seek.
- MP4, M4A, M4B, 3GP and QuickTime, read: the box tree, sample tables, a
  `moov` before or after the audio, fragmented files, edit lists, and
  chapters from a chapter track or a Nero `chpl` list. ALAC, Opus, FLAC and
  uncompressed audio inside decode and seek, and so does AMR; AC-3 is named
  with its length and refused.
- MP4 item lists, read and written: the standard atoms, freeform items,
  track, disc, tempo and compilation numbers, ReplayGain and cover art, with
  unmodelled items kept and only the `moov` box rebuilt. Changed chapters are
  written as a Nero `chpl` list.
- The iTunes `iTunSMPB` gapless record applied when decoding an MP4 file
  whose edit list does not trim it.
- Apple Lossless encoding into `.m4a` at 16, 20, 24 and 32 bits with one to
  eight channels, translated from Apple's reference encoder and writing the
  same packets, with tags, cover art and chapters.
- Raw AAC in ADTS and ADIF framing, probed: rate, channels and bitrate, and
  for ADTS the exact length from its frame headers.
- AAC-LC, decoded from MP4 and ADTS: every tool of the low complexity
  profile, every sampling rate, fixed and program channel configurations
  reordered into AudioKodec's channel order, edit-list trimming and
  sample-exact seeking. Written from ISO/IEC 14496-3 in floating point and
  agreeing with ffmpeg's decoder to within float rounding. HE-AAC, AAC Main,
  SSR and LTP, 960-sample frames and coupling channels are refused by name.
- WavPack, decoded: every encoding mode at 8 to 32 bits and 32-bit float,
  mono to multichannel in AudioKodec's speaker order, exact integers through
  `IntPcmAccess`, block checksums verified, and exact seeking. Hybrid files
  decode to their lossy stream. Translated from WavPack 5.9.0 and identical
  to `wvunpack`. DSD inside WavPack is refused by name.
- Musepack, decoded: stream version 8 at every quality, mono and stereo,
  with mid/side coding and noise substitution, the exact length its header
  states, and seeking. Translated from libmpcdec, BSD-licensed, and on the
  JVM identical to it float for float. Stream version 7 and older are refused
  by name.
- GSM 06.10, decoded from WAV files in Microsoft's layout and from
  QuickTime and MP4 in the plain one, translated from libgsm in its
  fixed-point arithmetic and identical to it sample for sample on every
  target, with seeking.
- iLBC, decoded in both frame sizes from `.lbc` files in the storage format
  of RFC 3952 and from CAF and QuickTime, translated from WebRTC in its
  fixed-point arithmetic and identical to it sample for sample on every
  target, with exact seeking.
- Tag writing into Ogg files that interleave several logical streams: the
  first audio stream's headers are rebuilt in place and every other
  stream's pages are copied byte for byte, in their original order.
- `Levels` for peak, RMS and clipping scans, silence at the ends, gain and
  linear or logarithmic fades, and `Signals` for sine, square, noise and
  silence.
- Waveform data, `WaveformReducer`: per-bucket minimum, maximum and RMS
  of each channel, pushed a block at a time and pulled in bounded memory,
  identical however the audio is split.
- Loudness measurement, `LoudnessMeter`: integrated, momentary and
  short-term loudness, loudness range and sample and true peak as EBU R 128
  defines them, translated from libebur128 1.2.6 and matching it, with
  ReplayGain 2 track and album values for the tag writers.
- Channel mixing between layouts, `Pcm.mixChannels`, with the downmix
  coefficients of ITU-R BS.775 and ffmpeg's rules for the positions BS.775
  leaves out, refusing a conversion with no defined answer; per-channel gain
  and mid-side conversion.
- Sample rate conversion: `Resampler`, streaming and allocation-free, and
  `Pcm.resample` for a whole buffer. The default mode is speexdsp 1.2.1's
  windowed sinc resampler at quality 10, translated sample for sample, with
  the output aligned to the input; a straight-line mode serves previews.
- DSD, read from DSF and DSDIFF files and converted to PCM at a
  thirty-second of its rate through three linear-phase decimating filters,
  stated in full, with channels in AudioKodec's order, the ID3v2 tags of
  both containers read, and exact seeking. Agrees with ffmpeg's conversion
  tone by tone within the filters' difference. DST compression is refused
  by name.
- G.722 and G.726, decoded from WAV and AU files, written from the ITU
  Recommendations and exact on every one of the ITU's test sequences, with
  exact seeking. `DecodeOptions.g722Mode` selects G.722's mode 2 or 3.
- AMR-NB and AMR-WB, decoded at every rate, from `.amr` and `.awb` files,
  multichannel ones included, and from 3GP and MP4: comfort noise,
  concealment of frames without data, AMR-WB decoder homing, and seeking.
  Translated from opencore-amr, Apache-licensed, in its fixed-point
  arithmetic, and identical to it sample for sample on every target.
- Speex inside Ogg, decoded: narrowband, wideband and ultra-wideband at every
  quality, variable bitrate, discontinuous transmission and in-band stereo,
  to the samples and length `speexdec` gives, and seeking. Translated from
  libspeex 1.2.1, BSD-licensed, and on the JVM identical to its float build
  float for float.
- True Audio, decoded: 8 to 24 bits including 20-bit audio, any number of
  channels, exact integers through `IntPcmAccess`, every CRC verified, and
  exact seeking. Translated from the format authors' BSD-licensed decoder
  and identical to both `tta -d` and ffmpeg. Password-protected files are
  refused by name.
- Monkey's Audio, decoded: every compression level from fast to insane, 8 to
  32 bits and 32-bit float, one to eight channels in AudioKodec's speaker
  order except seven, exact integers through `IntPcmAccess`, frame checksums
  verified, and exact seeking. Translated from the Monkey's Audio SDK 13.27
  and identical to `mac -d`. Files from before version 3.99 are refused by
  name.
- Apple Lossless, decoded from MP4 and CAF at 16, 20, 24 and 32 bits with one
  to eight channels, exactly, with integers through `IntPcmAccess`.
- Opus, decoded: SILK, CELT and the hybrid of the two, the redundant frames
  that smooth a change of codec, and every channel mapping,
  surround in AudioKodec's channel order and ambisonics with or without a
  demixing matrix. Pre-skip, end trim and the header's output gain are
  applied, and seeking runs 80 ms ahead of its target. Identical to libopus,
  sample for sample, and passes the RFC 8251 conformance vectors.
  The codec core also supports concealment of lost packets and recovery from
  the redundant copy the next packet carries.
- Tolerant Ogg playback preserves the complete prefix before a damaged page,
  missing page or broken continuation, with its duration and seek range.
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
