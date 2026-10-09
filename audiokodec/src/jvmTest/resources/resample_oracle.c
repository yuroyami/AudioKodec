/*
 * Converts interleaved 32-bit float PCM between sample rates with speexdsp's
 * resampler, aligned and trimmed as AudioKodec's Resampler is: the first
 * output frame is centred on the first input frame, the end is filled out
 * with zeros, and the stream converts to ceil(frames * out / in) frames.
 *
 * Usage: resample_oracle channels in_rate out_rate quality chunk < input > output
 */
#include <stdio.h>
#include <stdlib.h>
#include "speex_resampler.h"

int main(int argc, char **argv) {
    if (argc != 6) return 2;
    unsigned channels = (unsigned)atoi(argv[1]);
    unsigned in_rate = (unsigned)atoi(argv[2]);
    unsigned out_rate = (unsigned)atoi(argv[3]);
    int quality = atoi(argv[4]);
    unsigned chunk = (unsigned)atoi(argv[5]);
    size_t cap = 1 << 20, frames = 0;
    float *in = malloc(cap * channels * sizeof(float));
    size_t got;
    while ((got = fread(in + frames * channels, sizeof(float) * channels, cap - frames, stdin)) > 0) {
        frames += got;
        if (frames == cap) { cap *= 2; in = realloc(in, cap * channels * sizeof(float)); }
    }
    int err;
    SpeexResamplerState *st = speex_resampler_init(channels, in_rate, out_rate, quality, &err);
    if (!st) return 3;
    speex_resampler_skip_zeros(st);
    unsigned long long total = ((unsigned long long)frames * out_rate + in_rate - 1) / in_rate;
    size_t out_cap = total + 1024;
    float *out = malloc(out_cap * channels * sizeof(float));
    size_t written = 0, at = 0;
    while (at < frames) {
        spx_uint32_t in_len = (spx_uint32_t)(frames - at < chunk ? frames - at : chunk);
        spx_uint32_t out_len = (spx_uint32_t)(out_cap - written);
        speex_resampler_process_interleaved_float(st, in + at * channels, &in_len, out + written * channels, &out_len);
        at += in_len;
        written += out_len;
    }
    spx_uint32_t zeros = (spx_uint32_t)speex_resampler_get_input_latency(st);
    while (zeros > 0 && written < total) {
        spx_uint32_t in_len = zeros;
        spx_uint32_t out_len = (spx_uint32_t)(out_cap - written);
        speex_resampler_process_interleaved_float(st, NULL, &in_len, out + written * channels, &out_len);
        zeros -= in_len;
        written += out_len;
    }
    if (written > total) written = total;
    fwrite(out, sizeof(float) * channels, written, stdout);
    speex_resampler_destroy(st);
    return written == total ? 0 : 4;
}
