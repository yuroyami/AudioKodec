/*
 * Reads an Ogg file with libogg's public interface and prints, for every
 * packet of every logical stream in the order libogg delivers them, the
 * stream's serial number, the packet's number within its stream, its
 * granule position, its length and an FNV-1a hash of its bytes. libogg
 * rejects a page whose checksum is wrong, and a gap in a stream's page
 * sequence shows up as a hole, which this reports and fails on.
 *
 * Usage: ogg_packets file
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ogg/ogg.h>

#define MAX_STREAMS 16

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    FILE *f = fopen(argv[1], "rb");
    if (!f) return 3;
    ogg_sync_state sync;
    ogg_sync_init(&sync);
    ogg_stream_state streams[MAX_STREAMS];
    long serials[MAX_STREAMS];
    long counts[MAX_STREAMS];
    int n = 0, failed = 0;
    for (;;) {
        ogg_page page;
        int r = ogg_sync_pageout(&sync, &page);
        if (r < 0) { fprintf(stderr, "lost sync\n"); failed = 1; continue; }
        if (r == 0) {
            char *buffer = ogg_sync_buffer(&sync, 65536);
            size_t got = fread(buffer, 1, 65536, f);
            if (got == 0) break;
            ogg_sync_wrote(&sync, (long)got);
            continue;
        }
        long serial = ogg_page_serialno(&page);
        int s;
        for (s = 0; s < n; s++) if (serials[s] == serial) break;
        if (s == n) {
            if (n == MAX_STREAMS) return 4;
            ogg_stream_init(&streams[n], (int)serial);
            serials[n] = serial;
            counts[n] = 0;
            n++;
        }
        if (ogg_stream_pagein(&streams[s], &page) != 0) { fprintf(stderr, "page rejected\n"); failed = 1; }
        ogg_packet packet;
        int p;
        while ((p = ogg_stream_packetout(&streams[s], &packet)) != 0) {
            if (p < 0) { fprintf(stderr, "hole in stream %ld\n", serial); failed = 1; continue; }
            unsigned long h = 2166136261u;
            for (long i = 0; i < packet.bytes; i++) h = ((h ^ packet.packet[i]) * 16777619u) & 0xFFFFFFFFu;
            printf("%ld %ld %lld %ld %08lx\n", serial, counts[s]++, (long long)packet.granulepos, packet.bytes, h);
        }
    }
    fclose(f);
    return failed ? 5 : 0;
}
