/*
 * Measures interleaved 32-bit float PCM with libebur128 and prints its
 * integrated loudness, loudness range, momentary and short-term loudness at
 * the end, and each channel's sample peak, one figure a line with 17
 * significant digits.
 *
 * Usage: ebur128_oracle channels rate block < input
 */
#include <stdio.h>
#include <stdlib.h>
#include "ebur128.h"

int main(int argc, char **argv) {
    if (argc != 4) return 2;
    unsigned channels = (unsigned)atoi(argv[1]);
    unsigned long rate = strtoul(argv[2], NULL, 10);
    size_t block = (size_t)atoi(argv[3]);
    ebur128_state *st = ebur128_init(channels, rate, EBUR128_MODE_I | EBUR128_MODE_LRA | EBUR128_MODE_M | EBUR128_MODE_S | EBUR128_MODE_SAMPLE_PEAK);
    if (!st) return 3;
    if (channels == 8) {
        /* The canonical 7.1: the sides are surrounds too. */
        ebur128_set_channel(st, 6, EBUR128_LEFT_SURROUND);
        ebur128_set_channel(st, 7, EBUR128_RIGHT_SURROUND);
    }
    float *buffer = malloc(block * channels * sizeof(float));
    size_t got;
    while ((got = fread(buffer, sizeof(float) * channels, block, stdin)) > 0) {
        if (ebur128_add_frames_float(st, buffer, got) != EBUR128_SUCCESS) return 4;
    }
    double value;
    ebur128_loudness_global(st, &value); printf("%.17g\n", value);
    ebur128_loudness_range(st, &value); printf("%.17g\n", value);
    ebur128_loudness_momentary(st, &value); printf("%.17g\n", value);
    ebur128_loudness_shortterm(st, &value); printf("%.17g\n", value);
    for (unsigned c = 0; c < channels; c++) {
        ebur128_sample_peak(st, c, &value);
        printf("%.17g\n", value);
    }
    ebur128_destroy(&st);
    return 0;
}
