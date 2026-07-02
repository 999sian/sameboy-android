/* Two cores + a loopback transport, each running a hand-written serial ROM, on their own
   threads. Master sends 0xA5 (internal clock), slave 0x3C (external clock). After the
   transfer both SB registers must hold the OTHER byte. alarm() nets any deadlock. */
#include "../emulator.h"
#include "../link.h"
#include <Core/memory.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>

/* MBC-less 32KB ROM: entry jp 0x150; program at 0x150 loads SB, starts SC, spins on bit7. */
static uint8_t *make_link_rom(uint8_t sb, uint8_t sc, size_t *len) {
    size_t n = 32 * 1024;
    uint8_t *rom = calloc(1, n);
    rom[0x100] = 0x00; rom[0x101] = 0xC3; rom[0x102] = 0x50; rom[0x103] = 0x01;  /* jp 0x150 */
    uint8_t prog[] = {
        0x3E, sb,   0xE0, 0x01,   /* ld a,sb ; ldh (SB),a   */
        0x3E, sc,   0xE0, 0x02,   /* ld a,sc ; ldh (SC),a   */
        0xF0, 0x02, 0xE6, 0x80,   /* ldh a,(SC) ; and 0x80  */
        0x20, 0xFA,               /* jr nz,-6  (wait bit7)  */
        0x18, 0xFE,               /* jr -2     (done spin)  */
    };
    memcpy(&rom[0x150], prog, sizeof(prog));
    rom[0x147] = 0x00; rom[0x148] = 0x00; rom[0x149] = 0x00;   /* ROM only, no RAM */
    uint8_t c = 0; for (int i = 0x134; i <= 0x14C; i++) c = c - rom[i] - 1;
    rom[0x14D] = c;
    *len = n;
    return rom;
}

typedef struct { sb_emulator *e; int done_after; } runner;
static void *run_core(void *arg) {
    runner *r = arg;
    for (int i = 0; i < r->done_after; i++) sb_emu_run_frame(r->e);
    return NULL;
}

int main(void) {
    alarm(30);
    /* loopback transport unit check */
    sb_transport *x, *y = NULL; x = sb_transport_loopback_pair(&y);
    assert(x && y);
    assert(x->send(x, 0x11));
    uint8_t g; assert(y->recv(y, &g, 100) && g == 0x11);
    assert(!y->recv(y, &g, 0));          /* non-blocking empty → false */
    x->close(x); y->close(y);

    /* two-core exchange */
    size_t ml, sl;
    uint8_t *mrom = make_link_rom(0xA5, 0x81, &ml);   /* master: internal clock */
    uint8_t *srom = make_link_rom(0x3C, 0x80, &sl);   /* slave:  external clock */
    sb_emulator *m = sb_emu_create(0x002, mrom, ml, NULL, 0);
    sb_emulator *s = sb_emu_create(0x002, srom, sl, NULL, 0);
    assert(m && s);
    sb_emu_reset(m); sb_emu_reset(s);
    /* No audio consumer on these threads: drop samples so the ~100 ms ring
       never fills and blocks run_frame (see test_audio_drop_nonblocking). */
    static atomic_bool drop; atomic_init(&drop, true);
    sb_emu_set_audio_drop(m, &drop);
    sb_emu_set_audio_drop(s, &drop);

    sb_transport *ta, *tb = NULL; ta = sb_transport_loopback_pair(&tb);
    sb_emu_link_set(m, sb_link_create(ta));
    sb_emu_link_set(s, sb_link_create(tb));

    pthread_t mt, st;
    runner mr = { m, 600 }, sr = { s, 600 };   /* ~10s of frames; transfer completes early */
    pthread_create(&st, NULL, run_core, &sr);   /* start slave first so it polls */
    pthread_create(&mt, NULL, run_core, &mr);
    pthread_join(mt, NULL); pthread_join(st, NULL);

    /* Read SB via a tiny accessor rather than poking the opaque struct: */
    assert(sb_emu_peek_sb(m) == 0x3C);   /* master received slave's byte */
    assert(sb_emu_peek_sb(s) == 0xA5);   /* slave received master's byte */

    sb_emu_link_clear(m); sb_emu_link_clear(s);
    sb_emu_destroy(m); sb_emu_destroy(s);
    free(mrom); free(srom);
    printf("link: all tests passed\n");
    return 0;
}
