/*
 * Decodes an Ogg Vorbis file with libvorbisfile's public interface, which is
 * what oggdec is built on, and writes every sample as a little-endian 32-bit
 * float, interleaved in the order the file stores its channels.
 *
 * It prints one line: the channel count, the sample rate, what ov_pcm_total
 * says the length is, and how many frames ov_read_float actually returned.
 * A file libvorbisfile cannot open, or any error while decoding, including a
 * hole in the page sequence, is reported on stderr and fails.
 *
 * Usage: vorbisfile_oracle input.ogg output.f32
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vorbis/vorbisfile.h>

static void put_float(FILE *out, float value) {
    unsigned char bytes[4];
    unsigned int bits;
    memcpy(&bits, &value, 4);
    bytes[0] = (unsigned char)bits;
    bytes[1] = (unsigned char)(bits >> 8);
    bytes[2] = (unsigned char)(bits >> 16);
    bytes[3] = (unsigned char)(bits >> 24);
    fwrite(bytes, 1, 4, out);
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: vorbisfile_oracle input.ogg output.f32\n");
        return 2;
    }
    OggVorbis_File file;
    int opened = ov_fopen(argv[1], &file);
    if (opened != 0) {
        fprintf(stderr, "ov_fopen failed: %d\n", opened);
        return 3;
    }
    vorbis_info *info = ov_info(&file, -1);
    ogg_int64_t total = ov_pcm_total(&file, -1);
    FILE *out = fopen(argv[2], "wb");
    if (!out) return 4;
    long frames = 0;
    int section = 0;
    for (;;) {
        float **pcm;
        long got = ov_read_float(&file, &pcm, 4096, &section);
        if (got == 0) break;
        if (got < 0) {
            fprintf(stderr, "ov_read_float failed after %ld frames: %ld\n", frames, got);
            return 5;
        }
        for (long i = 0; i < got; i++) {
            for (int c = 0; c < info->channels; c++) put_float(out, pcm[c][i]);
        }
        frames += got;
    }
    fclose(out);
    printf("%d %ld %lld %ld\n", info->channels, info->rate, (long long)total, frames);
    ov_clear(&file);
    return 0;
}
