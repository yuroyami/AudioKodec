/*
 * A test oracle for AudioKodecs's Opus multistream decoder: a thin command
 * line over libopus's multistream and projection encoders and decoders, which
 * opus_demo does not expose. OpusMultistreamOracleTest compiles it against the
 * reference build and runs it; it is not part of the library.
 *
 *   opus_multistream_oracle -e <family> <channels> <bits per second> <samples per frame> in.pcm out.msb
 *   opus_multistream_oracle -d <sampling rate> in.msb out.f32
 *
 * The input is 16-bit little-endian PCM at 48 kHz. The .msb file holds "OPMS",
 * the mapping family, channel count, stream count and coupled stream count as
 * one byte each, then either the channel table (one byte per channel) or, for
 * family 3, the size of the demixing matrix as two big-endian bytes and the
 * matrix itself. Packets follow as in opus_demo's bit streams: a 4-byte
 * big-endian length, the encoder's 4-byte big-endian final range, then the
 * packet. A packet of length zero is a lost one.
 *
 * Decoding writes 32-bit little-endian floats, so that nothing is rounded
 * away before the comparison. A lost packet is concealed straight away for as
 * long as the packet before it lasted.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "opus_multistream.h"
#include "opus_projection.h"

#define MAX_PACKET 65536
#define MAX_FRAME 5760

static void put_be32(FILE *f, unsigned int v)
{
   unsigned char b[4] = {(unsigned char)(v >> 24), (unsigned char)(v >> 16), (unsigned char)(v >> 8), (unsigned char)v};
   fwrite(b, 1, 4, f);
}

static int get_be32(FILE *f, unsigned int *v)
{
   unsigned char b[4];
   if (fread(b, 1, 4, f) != 4) return 0;
   *v = ((unsigned int)b[0] << 24) | ((unsigned int)b[1] << 16) | ((unsigned int)b[2] << 8) | b[3];
   return 1;
}

static int encode(int argc, char **argv)
{
   int family, channels, bitrate, frame, streams, coupled, err, i;
   unsigned char mapping[255];
   unsigned char packet[MAX_PACKET];
   opus_int16 *pcm;
   unsigned char *raw;
   OpusMSEncoder *ms = NULL;
   OpusProjectionEncoder *proj = NULL;
   FILE *in, *out;
   if (argc != 8) return 1;
   family = atoi(argv[2]);
   channels = atoi(argv[3]);
   bitrate = atoi(argv[4]);
   frame = atoi(argv[5]);
   in = fopen(argv[6], "rb");
   out = fopen(argv[7], "wb");
   if (!in || !out) return 1;
   if (family == 3) {
      proj = opus_projection_ambisonics_encoder_create(48000, channels, 3, &streams, &coupled, OPUS_APPLICATION_AUDIO, &err);
      if (err != OPUS_OK) { fprintf(stderr, "encoder: %s\n", opus_strerror(err)); return 1; }
      opus_projection_encoder_ctl(proj, OPUS_SET_BITRATE(bitrate));
   } else {
      ms = opus_multistream_surround_encoder_create(48000, channels, family, &streams, &coupled, mapping, OPUS_APPLICATION_AUDIO, &err);
      if (err != OPUS_OK) { fprintf(stderr, "encoder: %s\n", opus_strerror(err)); return 1; }
      opus_multistream_encoder_ctl(ms, OPUS_SET_BITRATE(bitrate));
   }
   fwrite("OPMS", 1, 4, out);
   fputc(family, out);
   fputc(channels, out);
   fputc(streams, out);
   fputc(coupled, out);
   if (family == 3) {
      opus_int32 size;
      unsigned char *matrix;
      opus_projection_encoder_ctl(proj, OPUS_PROJECTION_GET_DEMIXING_MATRIX_SIZE(&size));
      matrix = malloc(size);
      opus_projection_encoder_ctl(proj, OPUS_PROJECTION_GET_DEMIXING_MATRIX(matrix, size));
      fputc(size >> 8, out);
      fputc(size & 0xFF, out);
      fwrite(matrix, 1, size, out);
      free(matrix);
   } else {
      fwrite(mapping, 1, channels, out);
   }
   pcm = malloc(sizeof(opus_int16) * frame * channels);
   raw = malloc(2 * frame * channels);
   for (;;) {
      size_t got = fread(raw, 2 * channels, frame, in);
      opus_uint32 range = 0;
      int len;
      if (got == 0) break;
      memset(pcm, 0, sizeof(opus_int16) * frame * channels);
      for (i = 0; i < (int)got * channels; i++) pcm[i] = (opus_int16)(raw[2 * i] | (raw[2 * i + 1] << 8));
      if (proj) {
         len = opus_projection_encode(proj, pcm, frame, packet, MAX_PACKET);
         opus_projection_encoder_ctl(proj, OPUS_GET_FINAL_RANGE(&range));
      } else {
         len = opus_multistream_encode(ms, pcm, frame, packet, MAX_PACKET);
         opus_multistream_encoder_ctl(ms, OPUS_GET_FINAL_RANGE(&range));
      }
      if (len < 0) { fprintf(stderr, "encode: %s\n", opus_strerror(len)); return 1; }
      put_be32(out, len);
      put_be32(out, range);
      fwrite(packet, 1, len, out);
   }
   fclose(in);
   fclose(out);
   return 0;
}

static int decode(int argc, char **argv)
{
   int rate, family, channels, streams, coupled, err, i, last = 0;
   unsigned char head[8];
   unsigned char mapping[255];
   unsigned char *matrix = NULL;
   unsigned char packet[MAX_PACKET];
   float *pcm;
   OpusMSDecoder *ms = NULL;
   OpusProjectionDecoder *proj = NULL;
   FILE *in, *out;
   if (argc != 5) return 1;
   rate = atoi(argv[2]);
   in = fopen(argv[3], "rb");
   out = fopen(argv[4], "wb");
   if (!in || !out) return 1;
   if (fread(head, 1, 8, in) != 8 || memcmp(head, "OPMS", 4) != 0) return 1;
   family = head[4];
   channels = head[5];
   streams = head[6];
   coupled = head[7];
   if (family == 3) {
      int size = fgetc(in) << 8;
      size |= fgetc(in);
      matrix = malloc(size);
      if (fread(matrix, 1, size, in) != (size_t)size) return 1;
      proj = opus_projection_decoder_create(rate, channels, streams, coupled, matrix, size, &err);
   } else {
      if (fread(mapping, 1, channels, in) != (size_t)channels) return 1;
      ms = opus_multistream_decoder_create(rate, channels, streams, coupled, mapping, &err);
   }
   if (err != OPUS_OK) { fprintf(stderr, "decoder: %s\n", opus_strerror(err)); return 1; }
   pcm = malloc(sizeof(float) * MAX_FRAME * channels);
   for (;;) {
      unsigned int len, range;
      int n;
      if (!get_be32(in, &len) || !get_be32(in, &range)) break;
      if (len > MAX_PACKET || fread(packet, 1, len, in) != len) return 1;
      if (proj) {
         n = len ? opus_projection_decode_float(proj, packet, len, pcm, MAX_FRAME, 0)
                 : opus_projection_decode_float(proj, NULL, 0, pcm, last, 0);
      } else {
         n = len ? opus_multistream_decode_float(ms, packet, len, pcm, MAX_FRAME, 0)
                 : opus_multistream_decode_float(ms, NULL, 0, pcm, last, 0);
      }
      if (n < 0) { fprintf(stderr, "decode: %s\n", opus_strerror(n)); return 1; }
      if (len) {
         opus_uint32 got;
         if (proj) opus_projection_decoder_ctl(proj, OPUS_GET_FINAL_RANGE(&got));
         else opus_multistream_decoder_ctl(ms, OPUS_GET_FINAL_RANGE(&got));
         if (got != range) { fprintf(stderr, "final range mismatch\n"); return 2; }
      }
      last = n;
      for (i = 0; i < n * channels; i++) {
         unsigned char b[4];
         unsigned int bits;
         memcpy(&bits, &pcm[i], 4);
         b[0] = bits; b[1] = bits >> 8; b[2] = bits >> 16; b[3] = bits >> 24;
         fwrite(b, 1, 4, out);
      }
   }
   fclose(in);
   fclose(out);
   return 0;
}

int main(int argc, char **argv)
{
   if (argc > 1 && strcmp(argv[1], "-e") == 0) return encode(argc, argv);
   if (argc > 1 && strcmp(argv[1], "-d") == 0) return decode(argc, argv);
   fprintf(stderr, "usage: see the comment at the top of the source\n");
   return 1;
}
