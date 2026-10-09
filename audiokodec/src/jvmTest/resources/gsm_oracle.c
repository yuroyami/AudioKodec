/*
 * The GSM 06.10 oracle: libgsm's own encoder and decoder over a stream of
 * frames, in the plain layout or in Microsoft's.
 *
 *   gsm_oracle plain in.gsm out.raw          33-byte frames, 160 samples each
 *   gsm_oracle wav49 in.bin out.raw          65-byte blocks of two frames, 320 samples each
 *   gsm_oracle encode-plain in.raw out.gsm   8 kHz 16-bit PCM to plain frames
 *   gsm_oracle encode-wav49 in.raw out.bin   8 kHz 16-bit PCM to Microsoft blocks
 *
 * Decoding writes every frame's 16-bit samples as gsm_decode gives them.
 * Encoding pads the PCM with silence to a whole frame or block.
 */
#include <stdio.h>
#include <string.h>
#include "gsm.h"

static int encode(int wav49, FILE *input, FILE *output) {
    gsm state = gsm_create();
    int one = 1;
    if (wav49) gsm_option(state, GSM_OPT_WAV49, &one);
    int samples = wav49 ? 320 : 160;
    gsm_signal pcm[320];
    unsigned char block[65];
    for (;;) {
        size_t got = fread(pcm, 2, samples, input);
        if (got == 0) break;
        memset(pcm + got, 0, (samples - got) * 2);
        gsm_encode(state, pcm, block);
        if (wav49) gsm_encode(state, pcm + 160, block + 32);
        fwrite(block, 1, wav49 ? 65 : 33, output);
        if (got < (size_t) samples) break;
    }
    gsm_destroy(state);
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: gsm_oracle plain|wav49|encode-plain|encode-wav49 in out\n");
        return 2;
    }
    if (strncmp(argv[1], "encode-", 7) == 0) {
        FILE *input = fopen(argv[2], "rb");
        FILE *output = fopen(argv[3], "wb");
        if (!input || !output) return 1;
        int result = encode(strcmp(argv[1], "encode-wav49") == 0, input, output);
        fclose(input);
        fclose(output);
        return result;
    }
    int wav49 = strcmp(argv[1], "wav49") == 0;
    FILE *input = fopen(argv[2], "rb");
    FILE *output = fopen(argv[3], "wb");
    if (!input || !output) return 1;
    gsm state = gsm_create();
    int one = 1;
    if (wav49) gsm_option(state, GSM_OPT_WAV49, &one);
    unsigned char block[65];
    gsm_signal pcm[320];
    int size = wav49 ? 65 : 33;
    while (fread(block, 1, size, input) == (size_t) size) {
        if (gsm_decode(state, block, pcm) < 0) return 3;
        if (wav49 && gsm_decode(state, block + 33, pcm + 160) < 0) return 3;
        fwrite(pcm, 2, wav49 ? 320 : 160, output);
    }
    gsm_destroy(state);
    fclose(input);
    fclose(output);
    return 0;
}
