/*
 * A test oracle for AudioKodec's Musepack decoder: a thin command line over
 * libmpcdec's public API, compiled by MusepackOracleTest against the libmpcdec
 * sources the port follows. The mpcdec tool writes only 16-bit samples; this
 * writes the decoder's own floats, so that nothing is rounded away before the
 * comparison. It is not part of the library.
 *
 *   mpc_float_oracle in.mpc out.f32 [sample to seek to]
 *
 * The output is interleaved 32-bit little-endian floats. The first line on
 * standard output gives the channel count, sample rate and stream length in
 * samples per channel.
 */
#include <stdio.h>
#include <stdlib.h>
#include <mpc/mpcdec.h>

int main(int argc, char **argv)
{
    mpc_reader reader;
    mpc_demux *demux;
    mpc_streaminfo info;
    MPC_SAMPLE_FORMAT buffer[MPC_DECODER_BUFFER_LENGTH];
    FILE *out;

    if (argc < 3) {
        fprintf(stderr, "usage: mpc_float_oracle in.mpc out.f32 [sample]\n");
        return 2;
    }
    if (sizeof(MPC_SAMPLE_FORMAT) != 4) {
        fprintf(stderr, "libmpcdec was built in fixed point\n");
        return 2;
    }
    if (mpc_reader_init_stdio(&reader, argv[1]) != MPC_STATUS_OK) return 1;
    demux = mpc_demux_init(&reader);
    if (!demux) return 1;
    mpc_demux_get_info(demux, &info);
    printf("%u %u %lld\n", info.channels, info.sample_freq, (long long) mpc_streaminfo_get_length_samples(&info));
    if (argc > 3 && mpc_demux_seek_sample(demux, (mpc_uint64_t) atoll(argv[3])) != MPC_STATUS_OK) return 1;
    out = fopen(argv[2], "wb");
    if (!out) return 1;
    for (;;) {
        mpc_frame_info frame;
        frame.buffer = buffer;
        if (mpc_demux_decode(demux, &frame) != MPC_STATUS_OK) return 1;
        if (frame.bits == -1) break;
        fwrite(buffer, sizeof(MPC_SAMPLE_FORMAT), (size_t) frame.samples * info.channels, out);
    }
    fclose(out);
    mpc_demux_exit(demux);
    mpc_reader_exit_stdio(&reader);
    return 0;
}
