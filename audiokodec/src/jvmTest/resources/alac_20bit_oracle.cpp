// Drives the public API of Apple's pinned Apache-2.0 ALAC reference encoder.
// See reference/REFERENCES.md. The PCM input contains signed, little-endian
// 32-bit integers; the encoder receives 20-bit PCM in high-aligned 24-bit slots.
#include "ALACEncoder.h"

#include <algorithm>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

using Bytes = std::vector<unsigned char>;

static void bigEndian(Bytes &out, uint64_t value, unsigned bytes) {
    for (unsigned i = bytes; i > 0; --i) out.push_back(static_cast<unsigned char>(value >> ((i - 1) * 8)));
}

static void text(Bytes &out, const char *value) {
    out.insert(out.end(), value, value + 4);
}

static void chunk(Bytes &out, const char *type, const Bytes &body) {
    text(out, type);
    bigEndian(out, body.size(), 8);
    out.insert(out.end(), body.begin(), body.end());
}

static void variableLength(Bytes &out, uint32_t value) {
    unsigned groups = 1;
    for (uint32_t rest = value >> 7; rest; rest >>= 7) ++groups;
    for (unsigned i = groups; i > 0; --i) {
        out.push_back(static_cast<unsigned char>(((value >> ((i - 1) * 7)) & 127) | (i > 1 ? 128 : 0)));
    }
}

static void check(int32_t status, const char *operation) {
    if (status != 0) throw std::runtime_error(std::string(operation) + " returned " + std::to_string(status));
}

int main(int argc, char **argv) {
    try {
        if (argc != 4) throw std::runtime_error("usage: alac_20bit_oracle channels input.s32le output.caf");
        const unsigned channels = std::stoul(argv[1]);
        if (channels < 1 || channels > kALACMaxChannels) throw std::runtime_error("invalid channel count");
        std::ifstream input(argv[2], std::ios::binary);
        if (!input) throw std::runtime_error("cannot open the PCM input");
        const Bytes raw((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
        if (raw.empty() || raw.size() % (channels * 4) != 0) throw std::runtime_error("incomplete PCM input");
        const uint64_t frames = raw.size() / (channels * 4);
        Bytes pcm;
        pcm.reserve(raw.size() / 4 * 3);
        for (size_t i = 0; i < raw.size(); i += 4) {
            const uint32_t word = uint32_t(raw[i]) | uint32_t(raw[i + 1]) << 8 |
                uint32_t(raw[i + 2]) << 16 | uint32_t(raw[i + 3]) << 24;
            // Verify the sign extension without converting an out-of-range unsigned word.
            if (word > 0x0007ffff && word < 0xfff80000) throw std::runtime_error("sample is outside signed 20-bit PCM");
            const uint32_t aligned = word << 4;
            for (unsigned k = 0; k < 3; ++k) {
                const unsigned shift = kALACFormatFlagsNativeEndian ? (2 - k) * 8 : k * 8;
                pcm.push_back(static_cast<unsigned char>(aligned >> shift));
            }
        }

        AudioFormatDescription source = {};
        source.mSampleRate = 48000;
        source.mFormatID = kALACFormatLinearPCM;
        source.mFormatFlags = kALACFormatFlagIsSignedInteger | kALACFormatFlagIsAlignedHigh | kALACFormatFlagsNativeEndian;
        source.mBytesPerPacket = source.mBytesPerFrame = channels * 3;
        source.mFramesPerPacket = 1;
        source.mChannelsPerFrame = channels;
        source.mBitsPerChannel = 20;
        AudioFormatDescription format = {};
        format.mSampleRate = source.mSampleRate;
        format.mFormatID = kALACFormatAppleLossless;
        format.mFormatFlags = 2; // Apple's lossless format selector for 20-bit source PCM.
        format.mFramesPerPacket = kALACDefaultFramesPerPacket;
        format.mChannelsPerFrame = channels;

        ALACEncoder encoder;
        encoder.SetFrameSize(format.mFramesPerPacket);
        check(encoder.InitializeEncoder(format), "InitializeEncoder");
        ALACSpecificConfig config = {};
        encoder.GetConfig(config);
        if (config.bitDepth != 20 || config.numChannels != channels) throw std::runtime_error("the encoder did not select 20-bit ALAC");
        Bytes audio;
        std::vector<uint32_t> sizes;
        Bytes packet(format.mFramesPerPacket * channels * 4 + 1024);
        for (uint64_t frame = 0; frame < frames; frame += format.mFramesPerPacket) {
            const unsigned count = static_cast<unsigned>(std::min<uint64_t>(format.mFramesPerPacket, frames - frame));
            int32_t size = count * source.mBytesPerFrame;
            check(encoder.Encode(source, format, pcm.data() + frame * source.mBytesPerFrame, packet.data(), &size), "Encode");
            if (size <= 0 || static_cast<size_t>(size) > packet.size()) throw std::runtime_error("invalid encoded packet length");
            sizes.push_back(size);
            audio.insert(audio.end(), packet.begin(), packet.begin() + size);
        }
        check(encoder.Finish(), "Finish");
        uint32_t cookieSize = encoder.GetMagicCookieSize(channels);
        Bytes cookie(cookieSize);
        encoder.GetMagicCookie(cookie.data(), &cookieSize);
        if (cookieSize > cookie.size()) throw std::runtime_error("invalid magic cookie length");
        cookie.resize(cookieSize);

        Bytes caf;
        text(caf, "caff");
        bigEndian(caf, 1, 2);
        bigEndian(caf, 0, 2);
        Bytes desc;
        uint64_t rate;
        static_assert(sizeof(rate) == sizeof(format.mSampleRate), "CAF needs an IEEE 64-bit sample rate");
        std::memcpy(&rate, &format.mSampleRate, sizeof(rate));
        bigEndian(desc, rate, 8);
        text(desc, "alac");
        bigEndian(desc, format.mFormatFlags, 4);
        bigEndian(desc, 0, 4); // Variable packet sizes.
        bigEndian(desc, format.mFramesPerPacket, 4);
        bigEndian(desc, channels, 4);
        bigEndian(desc, 0, 4); // The ALAC cookie carries the decoded bit depth.
        chunk(caf, "desc", desc);
        chunk(caf, "kuki", cookie);
        Bytes layout;
        bigEndian(layout, ALACChannelLayoutTags[channels - 1], 4);
        bigEndian(layout, 0, 4);
        bigEndian(layout, 0, 4);
        chunk(caf, "chan", layout);
        Bytes table;
        bigEndian(table, sizes.size(), 8);
        bigEndian(table, frames, 8);
        bigEndian(table, 0, 4);
        // CAF counts the unused part of the final nominal 4096-frame slot as
        // remainder, even though ALAC codes a shorter final packet explicitly.
        bigEndian(table, sizes.size() * uint64_t(format.mFramesPerPacket) - frames, 4);
        for (const auto size : sizes) variableLength(table, size);
        chunk(caf, "pakt", table);
        Bytes data;
        bigEndian(data, 0, 4); // CAF edit count.
        data.insert(data.end(), audio.begin(), audio.end());
        chunk(caf, "data", data);
        std::ofstream output(argv[3], std::ios::binary);
        output.write(reinterpret_cast<const char *>(caf.data()), caf.size());
        if (!output) throw std::runtime_error("cannot write the CAF output");
        return 0;
    } catch (const std::exception &failure) {
        std::cerr << failure.what() << '\n';
        return 1;
    }
}
