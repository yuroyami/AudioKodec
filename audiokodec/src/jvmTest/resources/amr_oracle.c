/*
 * The AMR oracle: opencore-amr's public decoder and encoder interfaces over
 * files in the storage format of RFC 4867.
 *
 *   amr_oracle encode MODE DTX in.raw out.amr   8 kHz 16-bit PCM to AMR
 *   amr_oracle decode in.amr|in.awb out.raw     every frame to 16-bit PCM
 *
 * A file whose PCM ends part way through a frame is padded with silence.
 * Decoding writes every frame's samples, as Decoder_Interface_Decode and
 * D_IF_decode produce them, and nothing else.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "interf_dec.h"
#include "interf_enc.h"
#include "dec_if.h"

static const int NB_SIZES[16] = {12, 13, 15, 17, 19, 20, 26, 31, 5, 6, 5, 5, 0, 0, 0, 0};
static const int WB_SIZES[16] = {17, 23, 32, 36, 40, 46, 50, 58, 60, 5, 0, 0, 0, 0, 0, 0};

static int encode(int mode, int dtx, const char *in, const char *out) {
    FILE *input = fopen(in, "rb");
    FILE *output = fopen(out, "wb");
    if (!input || !output) return 1;
    int samples = 160;
    fputs("#!AMR\n", output);
    void *state = Encoder_Interface_init(dtx);
    short pcm[160];
    unsigned char frame[64];
    for (;;) {
        size_t got = fread(pcm, 2, samples, input);
        if (got == 0) break;
        memset(pcm + got, 0, (samples - got) * 2);
        int bytes = Encoder_Interface_Encode(state, (enum Mode) mode, pcm, frame, 0);
        fwrite(frame, 1, bytes, output);
        if (got < (size_t) samples) break;
    }
    Encoder_Interface_exit(state);
    fclose(input);
    fclose(output);
    return 0;
}

static int decode(const char *in, const char *out) {
    FILE *input = fopen(in, "rb");
    FILE *output = fopen(out, "wb");
    if (!input || !output) return 1;
    char magic[9];
    if (fread(magic, 1, 6, input) != 6) return 1;
    int wide = 0;
    if (memcmp(magic, "#!AMR-", 6) == 0) {
        if (fread(magic + 6, 1, 3, input) != 3 || memcmp(magic, "#!AMR-WB\n", 9) != 0) return 1;
        wide = 1;
    } else if (memcmp(magic, "#!AMR\n", 6) != 0) {
        return 1;
    }
    void *state = wide ? D_IF_init() : Decoder_Interface_init();
    unsigned char frame[64];
    short pcm[320];
    int samples = wide ? 320 : 160;
    for (;;) {
        if (fread(frame, 1, 1, input) != 1) break;
        int size = (wide ? WB_SIZES : NB_SIZES)[(frame[0] >> 3) & 0x0f];
        if (fread(frame + 1, 1, size, input) != (size_t) size) break;
        if (wide) D_IF_decode(state, frame, pcm, 0); else Decoder_Interface_Decode(state, frame, pcm, 0);
        fwrite(pcm, 2, samples, output);
    }
    if (wide) D_IF_exit(state); else Decoder_Interface_exit(state);
    fclose(input);
    fclose(output);
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 6 && strcmp(argv[1], "encode") == 0) return encode(atoi(argv[2]), atoi(argv[3]), argv[4], argv[5]);
    if (argc == 4 && strcmp(argv[1], "decode") == 0) return decode(argv[2], argv[3]);
    fprintf(stderr, "usage: amr_oracle encode MODE DTX in out, or decode in out\n");
    return 2;
}
