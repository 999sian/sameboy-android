#pragma once
#include <stddef.h>
#include <stdint.h>
#include "ring_buffer.h"

typedef struct sb_emulator sb_emulator;

sb_emulator *sb_emu_create(int model, const uint8_t *rom, size_t rom_len,
                           const uint8_t *sav, size_t sav_len);
void         sb_emu_set_boot_rom(sb_emulator *e, int gb_boot_rom_type,
                                 const uint8_t *data, size_t len);
void         sb_emu_reset(sb_emulator *e);
void         sb_emu_run_frame(sb_emulator *e);
const uint32_t *sb_emu_front_buffer(sb_emulator *e, unsigned *w, unsigned *h);
/* Copies the last completed frame into dst (must hold >= 256*224 uint32_t)
   under the frame lock, so the render thread never reads a buffer the emu
   thread is mid-overwriting. Writes the frame dimensions to *w,*h. */
void         sb_emu_copy_front(sb_emulator *e, uint32_t *dst, unsigned *w, unsigned *h);
sb_ring     *sb_emu_audio_ring(sb_emulator *e);
void         sb_emu_set_key(sb_emulator *e, int gb_key_index, int pressed);
size_t       sb_emu_save_battery(sb_emulator *e, uint8_t **out_malloced);
/* Precondition: stop and join the emulation/render threads before calling. */
void         sb_emu_destroy(sb_emulator *e);

#define SB_AUDIO_SAMPLE_RATE 48000
