#include "../emulator.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <Core/gb.h>

/* Build a tiny valid ROM. battery=0: ROM-only, busy loop `inc a`.
   battery=1: MBC1+RAM+BATTERY (8KB RAM); program enables cart RAM and writes
   one byte to 0xA000, then busy-loops — a single battery-dirtying write. */
static uint8_t *make_rom(size_t *len, int battery)
{
    size_t n = 32 * 1024;
    uint8_t *rom = calloc(1, n);
    rom[0x100] = 0x00; rom[0x101] = 0xC3; rom[0x102] = 0x50; rom[0x103] = 0x01; /* nop; jp 0x150 */
    if (battery) {
        static const uint8_t prog[] = {
            0x3E, 0x0A,             /* ld a, 0x0A   */
            0xEA, 0x00, 0x00,       /* ld (0x0000), a  ; enable cart RAM */
            0x3E, 0x42,             /* ld a, 0x42   */
            0xEA, 0x00, 0xA0,       /* ld (0xA000), a  ; battery write */
            0x18, 0xFE,             /* jr -2        */
        };
        memcpy(&rom[0x150], prog, sizeof(prog));
        rom[0x147] = 0x03;          /* MBC1+RAM+BATTERY */
        rom[0x149] = 0x02;          /* 8KB cart RAM */
    }
    else {
        rom[0x150] = 0x3C; rom[0x151] = 0x18; rom[0x152] = 0xFE; /* inc a; jr -2 */
        rom[0x147] = 0x00;
        rom[0x149] = 0x00;
    }
    rom[0x148] = 0x00;
    uint8_t c = 0;
    for (int i = 0x134; i <= 0x14C; i++) c = c - rom[i] - 1;
    rom[0x14D] = c;
    *len = n;
    return rom;
}

static void drain(sb_emulator *e)
{
    static int16_t buf[4096 * 2];
    size_t got;
    while ((got = sb_ring_pop(sb_emu_audio_ring(e), buf, 4096)) > 0) {
        if (got < 4096) break;
    }
}

static void run_frames(sb_emulator *e, int n)
{
    for (int i = 0; i < n; i++) { sb_emu_run_frame(e); drain(e); }
}

/* Save → run on → load → save again must reproduce the identical state.
   (No RTC cart, so no wall-clock in the BESS payload.) */
static void test_state_roundtrip(void)
{
    size_t rlen; uint8_t *rom = make_rom(&rlen, 0);
    sb_emulator *e = sb_emu_create(0x002, rom, rlen, NULL, 0);
    assert(e);
    sb_emu_reset(e);
    run_frames(e, 30);

    uint8_t *a = NULL;
    size_t na = sb_emu_save_state(e, &a);
    assert(na > 0 && a);

    run_frames(e, 60);
    uint8_t *b = NULL;
    size_t nb = sb_emu_save_state(e, &b);
    assert(nb == na);
    assert(memcmp(a, b, na) != 0);          /* 60 frames of `inc a` changed state */
    free(b);

    assert(sb_emu_load_state(e, a, na) == 0);
    uint8_t *c = NULL;
    size_t nc = sb_emu_save_state(e, &c);
    assert(nc == na);
    assert(memcmp(a, c, na) == 0);          /* byte-exact restoration */
    free(a); free(c);
    sb_emu_destroy(e);
    free(rom);
}

static void test_rewind(void)
{
    size_t rlen; uint8_t *rom = make_rom(&rlen, 0);
    sb_emulator *e = sb_emu_create(0x002, rom, rlen, NULL, 0);
    assert(e);
    sb_emu_reset(e);
    /* NB: GB_reset itself pushes one rewind entry (gb.c:1795), so pop can
       succeed even before any frame runs — don't assert emptiness here. */
    run_frames(e, 120);
    assert(sb_emu_rewind_pop(e) == 1);      /* history exists (120s default from create) */
    int pops = 1;
    while (sb_emu_rewind_pop(e)) pops++;    /* must terminate */
    assert(pops > 1);
    run_frames(e, 4);
    assert(sb_emu_rewind_pop(e) == 1);      /* refills after exhaustion */
    sb_emu_destroy(e);
    free(rom);
}

static void test_model_switch_and_state_model(void)
{
    size_t rlen; uint8_t *rom = make_rom(&rlen, 0);
    sb_emulator *e = sb_emu_create(0x002, rom, rlen, NULL, 0);  /* DMG-B */
    assert(e);
    sb_emu_reset(e);
    run_frames(e, 5);

    sb_emu_switch_model(e, 0x205);          /* GB_MODEL_CGB_E */
    run_frames(e, 5);
    uint8_t *cgb = NULL;
    size_t ncgb = sb_emu_save_state(e, &cgb);
    assert(ncgb > 0);
    GB_model_t m = 0;
    assert(GB_get_state_model_from_buffer(cgb, ncgb, &m) == 0);
    assert(m == 0x205);
    sb_emu_destroy(e);

    /* auto model-switch on load: CGB state into a DMG emulator */
    e = sb_emu_create(0x002, rom, rlen, NULL, 0);
    assert(e);
    sb_emu_reset(e);
    run_frames(e, 5);
    assert(sb_emu_load_state(e, cgb, ncgb) == 0);
    uint8_t *now = NULL;
    size_t nnow = sb_emu_save_state(e, &now);
    assert(GB_get_state_model_from_buffer(now, nnow, &m) == 0);
    assert(m == 0x205);                     /* emulator followed the state's model */
    free(cgb); free(now);
    sb_emu_destroy(e);
    free(rom);
}

static void test_battery_dirty(void)
{
    size_t rlen; uint8_t *rom = make_rom(&rlen, 1);
    sb_emulator *e = sb_emu_create(0x002, rom, rlen, NULL, 0);
    assert(e);
    sb_emu_reset(e);
    run_frames(e, 30);                      /* program has written 0xA000 by now */
    assert(sb_emu_battery_dirty(e) == 1);
    uint8_t *sav = NULL;
    size_t nsav = sb_emu_save_battery(e, &sav);
    assert(nsav >= 0x2000 && sav && sav[0] == 0x42);
    free(sav);
    sb_emu_clear_battery_dirty(e);
    run_frames(e, 10);                      /* program only writes once */
    assert(sb_emu_battery_dirty(e) == 0);
    sb_emu_destroy(e);
    free(rom);
}

/* audio-drop flag: with drop set and nobody draining, run_frame must not block */
static void test_audio_drop_nonblocking(void)
{
    size_t rlen; uint8_t *rom = make_rom(&rlen, 0);
    sb_emulator *e = sb_emu_create(0x002, rom, rlen, NULL, 0);
    assert(e);
    sb_emu_reset(e);
    static atomic_bool drop;
    atomic_init(&drop, true);
    sb_emu_set_audio_drop(e, &drop);
    for (int i = 0; i < 10; i++) sb_emu_run_frame(e);  /* would deadlock without drop */
    sb_emu_destroy(e);
    free(rom);
}

int main(void)
{
    size_t rlen; uint8_t *rom = make_rom(&rlen, 0);
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
    test_state_roundtrip();
    test_rewind();
    test_model_switch_and_state_model();
    test_battery_dirty();
    test_audio_drop_nonblocking();
    printf("emulator: all tests passed\n");
    return 0;
}
