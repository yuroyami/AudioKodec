/*
 * A test oracle for AudioKodec's Speex decoder: libspeex's public float API
 * driven the way speexdec drives it, compiled by SpeexOracleTest against the
 * libspeex sources the port follows. speexdec rounds to 16-bit samples; this
 * writes the decoder's own floats, every frame of every packet, untrimmed, so
 * that nothing is rounded away and the test can check the trimming itself.
 * It is not part of the library.
 *
 *   speex_float_oracle in.spx out.f32
 *
 * The output is interleaved 32-bit little-endian floats on speexdec's scale,
 * where full scale is 32768. Standard output gets one line: the channel
 * count, the sample rate, the frame size, the decoder's lookahead, the
 * samples the first audio page decodes to less the samples its granule
 * position accounts for, the last granule position, and 1 when the first
 * audio page is also the last.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ogg/ogg.h>
#include <speex/speex.h>
#include <speex/speex_header.h>
#include <speex/speex_stereo.h>
#include <speex/speex_callbacks.h>

int main(int argc, char **argv)
{
    FILE *in, *out;
    ogg_sync_state oy;
    ogg_page og;
    ogg_packet op;
    ogg_stream_state os;
    int stream_init = 0, packet_count = 0, eos = 0;
    void *st = NULL;
    SpeexBits bits;
    SpeexStereoState stereo = SPEEX_STEREO_STATE_INIT;
    spx_int32_t frame_size = 0, lookahead = 0, rate = 0;
    int nframes = 1, channels = 1, extra_headers = 0;
    long long last_granule = 0, final_granule = 0, first_deficit = 0;
    int first_audio_page = 1, audio_on_page = 0, first_is_last = 0;
    float output[2000];

    if (argc < 3) {
        fprintf(stderr, "usage: speex_float_oracle in.spx out.f32\n");
        return 2;
    }
    in = fopen(argv[1], "rb");
    out = fopen(argv[2], "wb");
    if (!in || !out) return 1;
    ogg_sync_init(&oy);
    speex_bits_init(&bits);
    for (;;) {
        char *data = ogg_sync_buffer(&oy, 4096);
        size_t got = fread(data, 1, 4096, in);
        ogg_sync_wrote(&oy, (long) got);
        while (ogg_sync_pageout(&oy, &og) == 1) {
            long long granule;
            int page_packets;
            if (!stream_init) {
                ogg_stream_init(&os, ogg_page_serialno(&og));
                stream_init = 1;
            }
            ogg_stream_pagein(&os, &og);
            granule = ogg_page_granulepos(&og);
            page_packets = ogg_page_packets(&og);
            audio_on_page = 0;
            while (!eos && ogg_stream_packetout(&os, &op) == 1) {
                if (packet_count == 0) {
                    SpeexHeader *header = speex_packet_to_header((char *) op.packet, (int) op.bytes);
                    spx_int32_t enh = 1;
                    if (!header || header->mode < 0 || header->mode >= SPEEX_NB_MODES) return 1;
                    st = speex_decoder_init(speex_lib_get_mode(header->mode));
                    speex_decoder_ctl(st, SPEEX_SET_ENH, &enh);
                    speex_decoder_ctl(st, SPEEX_GET_FRAME_SIZE, &frame_size);
                    rate = header->rate;
                    speex_decoder_ctl(st, SPEEX_SET_SAMPLING_RATE, &rate);
                    speex_decoder_ctl(st, SPEEX_GET_LOOKAHEAD, &lookahead);
                    nframes = header->frames_per_packet ? header->frames_per_packet : 1;
                    channels = header->nb_channels == 1 ? 1 : 2;
                    extra_headers = header->extra_headers;
                    if (channels == 2) {
                        SpeexCallback callback;
                        callback.callback_id = SPEEX_INBAND_STEREO;
                        callback.func = speex_std_stereo_request_handler;
                        callback.data = &stereo;
                        speex_decoder_ctl(st, SPEEX_SET_HANDLER, &callback);
                    }
                    free(header);
                } else if (packet_count > 1 + extra_headers) {
                    int j;
                    audio_on_page++;
                    if (op.e_o_s) eos = 1;
                    speex_bits_read_from(&bits, (char *) op.packet, (int) op.bytes);
                    for (j = 0; j != nframes; j++) {
                        int ret = speex_decode(st, &bits, output);
                        if (ret == -1 || ret == -2 || speex_bits_remaining(&bits) < 0) break;
                        if (channels == 2) speex_decode_stereo(output, frame_size, &stereo);
                        fwrite(output, sizeof(float), (size_t) frame_size * channels, out);
                    }
                }
                packet_count++;
            }
            /* As speexdec reckons it: what the page's packets decode to, less what its granule position advanced. */
            if (audio_on_page && granule > 0) {
                if (first_audio_page) {
                    first_deficit = (long long) page_packets * nframes * frame_size - (granule - last_granule);
                    first_is_last = ogg_page_eos(&og) ? 1 : 0;
                    first_audio_page = 0;
                }
                final_granule = granule;
            }
            if (granule > 0) last_granule = granule;
        }
        if (got == 0) break;
    }
    printf("%d %d %d %d %lld %lld %d\n", channels, rate, frame_size, lookahead, first_deficit, final_granule, first_is_last);
    fclose(out);
    return 0;
}
