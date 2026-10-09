/*
 * Encodes 16 kHz 16-bit PCM to an AMR-WB file in the storage format of
 * RFC 4867 with vo-amrwbenc, for the AMR oracle tests.
 *
 *   amrwb_encoder MODE DTX in.raw out.awb
 *
 * A file whose PCM ends part way through a frame is padded with silence.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "enc_if.h"

int main(int argc, char **argv) {
    if (argc != 5) {
        fprintf(stderr, "usage: amrwb_encoder MODE DTX in out\n");
        return 2;
    }
    int mode = atoi(argv[1]);
    int dtx = atoi(argv[2]);
    FILE *input = fopen(argv[3], "rb");
    FILE *output = fopen(argv[4], "wb");
    if (!input || !output) return 1;
    fputs("#!AMR-WB\n", output);
    void *state = E_IF_init();
    short pcm[320];
    unsigned char frame[64];
    for (;;) {
        size_t got = fread(pcm, 2, 320, input);
        if (got == 0) break;
        memset(pcm + got, 0, (320 - got) * 2);
        int bytes = E_IF_encode(state, mode, pcm, frame, dtx);
        fwrite(frame, 1, bytes, output);
        if (got < 320) break;
    }
    E_IF_exit(state);
    fclose(input);
    fclose(output);
    return 0;
}
