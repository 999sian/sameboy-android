#include "../emulator.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

/* Build a tiny valid-enough ROM: 32KB, Nintendo logo + header checksum so the
   boot ROM (if any) accepts it; without a boot ROM SameBoy still runs. */
static uint8_t *make_rom(size_t *len)
{
    size_t n = 32 * 1024;
    uint8_t *rom = calloc(1, n);
    /* entry: nop; jp 0x0150 */
    rom[0x100] = 0x00; rom[0x101] = 0xC3; rom[0x102] = 0x50; rom[0x103] = 0x01;
    /* a trivial program at 0x150: inc a; jr -2 (busy loop toggling A) */
    rom[0x150] = 0x3C; rom[0x151] = 0x18; rom[0x152] = 0xFE;
    rom[0x147] = 0x00; /* MBC: ROM ONLY */
    rom[0x148] = 0x00; /* 32KB */
    rom[0x149] = 0x00; /* no RAM */
    /* header checksum (0x134..0x14C) */
    uint8_t c = 0;
    for (int i = 0x134; i <= 0x14C; i++) c = c - rom[i] - 1;
    rom[0x14D] = c;
    *len = n;
    return rom;
}

int main(void)
{
    size_t rlen; uint8_t *rom = make_rom(&rlen);
    /* GB_MODEL_DMG_B = 0x002 */
    sb_emulator *e = sb_emu_create(0x002, rom, rlen, NULL, 0);
    assert(e != NULL);
    sb_emu_reset(e);

    /* Step frames while draining the audio ring each frame, exactly as the
       real AAudio consumer would. With the ~100 ms runtime ring, NOT draining
       would block the (single-threaded) producer once it fills. */
    size_t total_samples = 0;
    int16_t drain[4096 * 2];
    for (int i = 0; i < 60; i++) {          /* ~1 second */
        sb_emu_run_frame(e);
        size_t got;
        while ((got = sb_ring_pop(sb_emu_audio_ring(e), drain, 4096)) > 0) {
            total_samples += got;
            if (got < 4096) break;          /* ring drained for this frame */
        }
    }

    unsigned w = 0, h = 0;
    const uint32_t *fb = sb_emu_front_buffer(e, &w, &h);
    assert(fb != NULL);
    assert(w == 160 && h == 144);               /* DMG screen, no border */
    /* every pixel must be fully opaque (alpha 0xFF) from our rgb_encode */
    for (unsigned i = 0; i < w * h; i++) assert((fb[i] & 0xFF000000u) == 0xFF000000u);

    /* render-path API: copy_front must agree with front_buffer */
    static uint32_t staging[256 * 224];
    unsigned cw = 0, ch = 0;
    sb_emu_copy_front(e, staging, &cw, &ch);
    assert(cw == w && ch == h);
    for (unsigned i = 0; i < cw * ch; i++) assert((staging[i] & 0xFF000000u) == 0xFF000000u);

    /* running ~1 s must have produced roughly SAMPLE_RATE stereo frames */
    assert(total_samples > 0);

    sb_emu_destroy(e);
    free(rom);
    printf("emulator: all tests passed\n");
    return 0;
}
