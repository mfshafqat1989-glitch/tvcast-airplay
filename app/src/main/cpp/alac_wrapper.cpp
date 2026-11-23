// Small C wrapper around Apple's ALAC decoder (Apache 2.0) for AirPlay music (44100 Hz / 16-bit / stereo).
#include <cstdint>
#include <cstring>
#include "ALACDecoder.h"
#include "ALACBitUtilities.h"

extern "C" {

static ALACDecoder *g_alac = nullptr;

int alac_wrapper_init(void) {
    // ALACSpecificConfig used by AirPlay: frameLength 352, 16 bit, pb 40, mb 10, kb 14,
    // 2 channels, maxRun 255, sample rate 44100 (all big-endian).
    static const uint8_t cookie[24] = {
        0x00, 0x00, 0x01, 0x60,  0x00, 0x10, 0x28, 0x0a,  0x0e, 0x02, 0x00, 0xff,
        0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0x00, 0x00,  0x00, 0x00, 0xac, 0x44 };
    delete g_alac;
    g_alac = new ALACDecoder();
    return g_alac->Init((void *) cookie, sizeof(cookie));
}

/* Decodes one ALAC frame into interleaved 16-bit PCM. Returns bytes written (0 on error). */
int alac_wrapper_decode(const unsigned char *in, int in_len, unsigned char *out, int out_cap) {
    if (!g_alac || in_len <= 0) return 0;
    const uint32_t frames = 352, channels = 2;
    if (out_cap < (int) (frames * channels * 2)) return 0;
    BitBuffer bits;
    BitBufferInit(&bits, (uint8_t *) in, (uint32_t) in_len);
    uint32_t got = 0;
    if (g_alac->Decode(&bits, out, frames, channels, &got) != 0) return 0;
    return (int) (got * channels * 2);
}

void alac_wrapper_destroy(void) {
    delete g_alac;
    g_alac = nullptr;
}

}
