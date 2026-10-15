/*
 * Test oracle for AudioKodec's Opus encoder, over the pinned libopus build and
 * libogg, both through their own interfaces.
 *
 *   opus_encode_oracle decode in.opus out.f32
 *     Reads an Ogg Opus file with libogg, checks its headers and pages, decodes
 *     every packet with opus_decode_float at 48 kHz, and applies the pre-skip,
 *     the end trim and the output gain as RFC 7845 says. Writes the result as
 *     interleaved float32 and describes the stream on standard output: the
 *     identification header, the vendor string, every audio packet's size,
 *     table-of-contents byte and samples, and every page's granule position.
 *     Exits 2 for a framing or header error and 3 for a decoding error.
 *
 *   opus_encode_oracle celt channels bitrate vbr constrained frame in.f32 out.bit
 *     Encodes float32 input with libopus's CELT encoder at complexity 10, driven
 *     as libopus's CELT-only mode drives it without its tonality analysis: a
 *     3 Hz DC rejection, then celt_encode_with_ec with the rate, the full band
 *     and prediction on. Writes each packet as a 4-byte big-endian length, the
 *     encoder's 4-byte big-endian final range and the packet, as opus_demo does.
 *     Each packet starts with a CELT-only fullband table-of-contents byte, so
 *     opus_demo -d can decode the file.
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ogg/ogg.h>
#include "opus.h"
#include "celt.h"

static int fail(int code, const char *what)
{
   fprintf(stderr, "%s\n", what);
   return code;
}

static unsigned le16(const unsigned char *p) { return p[0] | p[1] << 8; }
static unsigned long le32(const unsigned char *p) { return p[0] | p[1] << 8 | p[2] << 16 | (unsigned long)p[3] << 24; }

static int decode(const char *in_path, const char *out_path)
{
   FILE *in = fopen(in_path, "rb");
   if (!in) return fail(1, "cannot open input");
   ogg_sync_state sync;
   ogg_stream_state stream;
   ogg_page page;
   ogg_packet packet;
   ogg_sync_init(&sync);
   int stream_ready = 0, packets = 0, channels = 0, pre_skip = 0, gain = 0, eos_seen = 0, pages = 0;
   OpusDecoder *decoder = NULL;
   size_t capacity = 48000 * 2, used = 0;
   float *pcm = malloc(sizeof(float) * capacity);
   float frame[5760 * 2];
   long long decoded = 0, last_granule = -1, final_granule = -1;
   for (;;) {
      int got = ogg_sync_pageout(&sync, &page);
      if (got < 0) return fail(2, "lost page synchronisation or a bad page checksum");
      if (got == 0) {
         char *buffer = ogg_sync_buffer(&sync, 65536);
         size_t bytes = fread(buffer, 1, 65536, in);
         if (bytes == 0) break;
         ogg_sync_wrote(&sync, (long)bytes);
         continue;
      }
      if (eos_seen) return fail(2, "a page after the end of the stream");
      if (!stream_ready) {
         if (!ogg_page_bos(&page)) return fail(2, "the first page is not a beginning of stream");
         ogg_stream_init(&stream, ogg_page_serialno(&page));
         stream_ready = 1;
      } else if (ogg_page_bos(&page)) {
         return fail(2, "a second beginning of stream");
      }
      if (ogg_page_pageno(&page) != pages) return fail(2, "page sequence numbers are not consecutive");
      pages++;
      if (ogg_stream_pagein(&stream, &page) != 0) return fail(2, "libogg refused a page");
      int packets_on_page = 0;
      for (;;) {
         int out = ogg_stream_packetout(&stream, &packet);
         if (out < 0) return fail(2, "a hole in the packet sequence");
         if (out == 0) break;
         packets_on_page++;
         if (packets == 0) {
            if (packet.bytes < 19 || memcmp(packet.packet, "OpusHead", 8) != 0) return fail(2, "no OpusHead");
            channels = packet.packet[9];
            pre_skip = le16(packet.packet + 10);
            gain = (short)le16(packet.packet + 16);
            printf("head %d %d %d %lu %d %d %ld\n", packet.packet[8], channels, pre_skip,
                   le32(packet.packet + 12), gain, packet.packet[18], packet.bytes);
            if (packet.packet[18] != 0 || channels < 1 || channels > 2) return fail(2, "not mapping family 0");
            int error;
            decoder = opus_decoder_create(48000, channels, &error);
            if (error != OPUS_OK) return fail(3, "opus_decoder_create failed");
            if (ogg_page_granulepos(&page) != 0) return fail(2, "the OpusHead page's granule is not zero");
         } else if (packets == 1) {
            if (packet.bytes < 16 || memcmp(packet.packet, "OpusTags", 8) != 0) return fail(2, "no OpusTags");
            unsigned long vendor = le32(packet.packet + 8);
            if (vendor > (unsigned long)packet.bytes - 16) return fail(2, "a vendor string longer than the packet");
            printf("tags %.*s\n", (int)vendor, packet.packet + 12);
            printf("comments %lu\n", le32(packet.packet + 12 + vendor));
         } else {
            int samples = opus_packet_get_nb_samples(packet.packet, packet.bytes, 48000);
            if (samples <= 0) return fail(3, "a packet with no samples");
            int n = opus_decode_float(decoder, packet.packet, packet.bytes, frame, 5760, 0);
            if (n != samples) return fail(3, "opus_decode_float failed");
            opus_uint32 range;
            opus_decoder_ctl(decoder, OPUS_GET_FINAL_RANGE(&range));
            printf("packet %ld %d %d %u\n", packet.bytes, packet.packet[0], samples, range);
            if (used + (size_t)n * channels > capacity) {
               capacity = 2 * capacity + (size_t)n * channels;
               pcm = realloc(pcm, sizeof(float) * capacity);
            }
            memcpy(pcm + used, frame, sizeof(float) * n * channels);
            used += (size_t)n * channels;
            decoded += n;
         }
         packets++;
      }
      long long granule = ogg_page_granulepos(&page);
      printf("page %lld %d %d %d\n", granule, packets_on_page, ogg_page_eos(&page), ogg_page_continued(&page));
      if (packets >= 2 && packets_on_page > 0 && packets > 2) {
         if (granule < last_granule) return fail(2, "granule positions go backwards");
         if (!ogg_page_eos(&page) && granule != decoded) return fail(2, "a page's granule position is not the samples so far");
         last_granule = granule;
      }
      if (ogg_page_eos(&page)) {
         eos_seen = 1;
         final_granule = granule;
      }
   }
   if (!eos_seen) return fail(2, "no end of stream page");
   if (packets < 3) return fail(2, "no audio packets");
   /* RFC 7845 section 4.5: the last page's granule position ends the stream. */
   if (final_granule > decoded) return fail(2, "the final granule position is past the decoded samples");
   long long start = pre_skip, end = final_granule;
   if (end < start) end = start;
   printf("end %lld %lld %lld\n", final_granule, decoded, end - start);
   double scale = pow(10.0, gain / (20.0 * 256.0));
   FILE *out = fopen(out_path, "wb");
   for (long long i = start * channels; i < end * channels; i++) {
      float value = (float)(pcm[i] * scale);
      if (gain == 0) value = pcm[i];
      fwrite(&value, sizeof(float), 1, out);
   }
   fclose(out);
   opus_decoder_destroy(decoder);
   ogg_stream_clear(&stream);
   ogg_sync_clear(&sync);
   free(pcm);
   fclose(in);
   return 0;
}

static void dc_reject(const float *in, float *out, float *hp_mem, int len, int channels)
{
   /* opus_encoder.c's float dc_reject at a 3 Hz cutoff. */
   float coef = 6.3f * 3 / 48000, coef2 = 1 - coef;
   int i;
   if (channels == 2) {
      float m0 = hp_mem[0], m2 = hp_mem[2];
      for (i = 0; i < len; i++) {
         float x0 = in[2 * i], x1 = in[2 * i + 1];
         float out0 = x0 - m0, out1 = x1 - m2;
         m0 = coef * x0 + 1e-30f + coef2 * m0;
         m2 = coef * x1 + 1e-30f + coef2 * m2;
         out[2 * i] = out0;
         out[2 * i + 1] = out1;
      }
      hp_mem[0] = m0;
      hp_mem[2] = m2;
   } else {
      float m0 = hp_mem[0];
      for (i = 0; i < len; i++) {
         float x = in[i];
         float y = x - m0;
         m0 = coef * x + 1e-30f + coef2 * m0;
         out[i] = y;
      }
      hp_mem[0] = m0;
   }
}

static int celt(int channels, int bitrate, int vbr, int constrained, int frame, const char *in_path, const char *out_path)
{
   FILE *in = fopen(in_path, "rb"), *out = fopen(out_path, "wb");
   if (!in || !out) return fail(1, "cannot open the files");
   CELTEncoder *st = malloc(celt_encoder_get_size(channels));
   if (celt_encoder_init(st, 48000, channels, 0) != OPUS_OK) return fail(3, "celt_encoder_init failed");
   celt_encoder_ctl(st, CELT_SET_SIGNALLING(0));
   celt_encoder_ctl(st, OPUS_SET_COMPLEXITY(10));
   celt_encoder_ctl(st, OPUS_SET_LSB_DEPTH(24));
   celt_encoder_ctl(st, CELT_SET_END_BAND(21));
   celt_encoder_ctl(st, CELT_SET_CHANNELS(channels));
   celt_encoder_ctl(st, CELT_SET_PREDICTION(2));
   celt_encoder_ctl(st, CELT_SET_START_BAND(0));
   int bytes;
   if (vbr) {
      celt_encoder_ctl(st, OPUS_SET_VBR(1));
      celt_encoder_ctl(st, OPUS_SET_VBR_CONSTRAINT(constrained));
      celt_encoder_ctl(st, OPUS_SET_BITRATE(bitrate));
      bytes = 1275;
   } else {
      int rate12 = 12 * 48000 / frame;
      bytes = (12 * bitrate / 8 + rate12 / 2) / rate12;
      if (bytes > 1276) bytes = 1276;
      bytes -= 1;
      celt_encoder_ctl(st, OPUS_SET_VBR(0));
      celt_encoder_ctl(st, OPUS_SET_BITRATE(OPUS_BITRATE_MAX));
   }
   float *input = malloc(sizeof(float) * frame * channels), *buffer = malloc(sizeof(float) * frame * channels);
   float hp_mem[4] = {0, 0, 0, 0};
   unsigned char packet[1276];
   for (;;) {
      size_t got = fread(input, sizeof(float) * channels, frame, in);
      if (got == 0) break;
      if ((int)got < frame) memset(input + got * channels, 0, sizeof(float) * channels * (frame - got));
      dc_reject(input, buffer, hp_mem, frame, channels);
      float energy = 0;
      for (int i = 0; i < frame * channels; i++) energy += buffer[i] * buffer[i];
      if (!(energy < 1e9f) || energy != energy) {
         memset(buffer, 0, sizeof(float) * frame * channels);
         memset(hp_mem, 0, sizeof(hp_mem));
      }
      int length = celt_encode_with_ec(st, buffer, frame, packet + 1, bytes, NULL);
      if (length < 0) return fail(3, "celt_encode_with_ec failed");
      opus_uint32 range;
      celt_encoder_ctl(st, OPUS_GET_FINAL_RANGE(&range));
      int total = length + 1;
      /* CELT-only fullband: configuration 28 plus the frame size's step from 2.5 ms. */
      int period = 0;
      for (int rate = 48000 / frame; rate < 400; rate <<= 1) period++;
      packet[0] = (unsigned char)(0x80 | 3 << 5 | period << 3 | (channels == 2) << 2);
      unsigned char head[8] = {
         (unsigned char)(total >> 24), (unsigned char)(total >> 16), (unsigned char)(total >> 8), (unsigned char)total,
         (unsigned char)(range >> 24), (unsigned char)(range >> 16), (unsigned char)(range >> 8), (unsigned char)range,
      };
      fwrite(head, 1, 8, out);
      fwrite(packet, 1, total, out);
   }
   fclose(out);
   fclose(in);
   free(st);
   return 0;
}

int main(int argc, char **argv)
{
   if (argc == 4 && strcmp(argv[1], "decode") == 0) return decode(argv[2], argv[3]);
   if (argc == 9 && strcmp(argv[1], "celt") == 0) {
      return celt(atoi(argv[2]), atoi(argv[3]), atoi(argv[4]), atoi(argv[5]), atoi(argv[6]), argv[7], argv[8]);
   }
   fprintf(stderr, "usage: %s decode in.opus out.f32\n"
                   "       %s celt channels bitrate vbr constrained frame in.f32 out.bit\n", argv[0], argv[0]);
   return 1;
}
