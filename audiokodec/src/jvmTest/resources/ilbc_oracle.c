/*
 * The iLBC oracle for IlbcOracleTest: a few lines over the public interface
 * of libilbc, WebRTC's iLBC, built from the pinned sources under
 * reference/ilbc. Only run, never shipped.
 *
 *   ilbc_oracle encode <20|30> in.pcm out.raw   16-bit mono PCM to frames
 *   ilbc_oracle decode <20|30> in.raw out.pcm   frames to 16-bit PCM
 *
 * Decoding stops at the first frame the decoder refuses, and says so.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ilbc.h"

int main(int argc, char **argv) {
    if (argc != 5) return 2;
    int mode = atoi(argv[2]);
    size_t samples = mode == 30 ? 240 : 160, bytes = mode == 30 ? 50 : 38;
    FILE *in = fopen(argv[3], "rb"), *out = fopen(argv[4], "wb");
    if (!in || !out) return 2;
    if (strcmp(argv[1], "encode") == 0) {
        IlbcEncoderInstance *enc;
        WebRtcIlbcfix_EncoderCreate(&enc);
        WebRtcIlbcfix_EncoderInit(enc, (int16_t) mode);
        int16_t pcm[240];
        uint8_t frame[50];
        size_t got;
        while ((got = fread(pcm, 2, samples, in)) > 0) {
            if (got < samples) memset(pcm + got, 0, (samples - got) * 2);
            if (WebRtcIlbcfix_Encode(enc, pcm, samples, frame) != (int) bytes) return 1;
            fwrite(frame, 1, bytes, out);
        }
    } else {
        IlbcDecoderInstance *dec;
        WebRtcIlbcfix_DecoderCreate(&dec);
        WebRtcIlbcfix_DecoderInit(dec, (int16_t) mode);
        uint8_t frame[50];
        int16_t pcm[240], type;
        long n = 0;
        while (fread(frame, 1, bytes, in) == bytes) {
            if (WebRtcIlbcfix_Decode(dec, frame, bytes, pcm, &type) != (int) samples) {
                fprintf(stderr, "refused frame %ld\n", n);
                break;
            }
            fwrite(pcm, 2, samples, out);
            n++;
        }
    }
    fclose(out);
    return 0;
}
