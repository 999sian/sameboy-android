#pragma once
#include <stdint.h>
#include <stddef.h>
#include <android/native_window.h>
#include "emulator.h"

typedef struct sb_session sb_session;

/* All functions tolerate s == NULL (no-op; save_battery returns 0).
   sb_session_start takes ownership of win even when it bails out early. */

sb_session *sb_session_create(int model, const uint8_t *rom, size_t rom_len,
                              const uint8_t *sav, size_t sav_len);
void        sb_session_set_boot_rom(sb_session *s, int type, const uint8_t *data, size_t len);
void        sb_session_reset(sb_session *s);
void        sb_session_start(sb_session *s, ANativeWindow *win);   /* start threads */
void        sb_session_stop(sb_session *s);                        /* stop threads */
void        sb_session_pause(sb_session *s, int paused);
void        sb_session_set_key(sb_session *s, int idx, int pressed);
size_t      sb_session_save_battery(sb_session *s, uint8_t **out);
void        sb_session_destroy(sb_session *s);
