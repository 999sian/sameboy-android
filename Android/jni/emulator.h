#pragma once
#include <stddef.h>
#include <stdint.h>
#include "ring_buffer.h"
#include <stdatomic.h>

#define SB_MAX_W 256
#define SB_MAX_H 224
#define SB_FB_MAX (SB_MAX_W * SB_MAX_H)

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

void   sb_emu_set_audio_drop(sb_emulator *e, const atomic_bool *drop_on_full); /* NULL = always block */
size_t sb_emu_save_state(sb_emulator *e, uint8_t **out_malloced);       /* 0 on failure; caller frees */
int    sb_emu_load_state(sb_emulator *e, const uint8_t *buf, size_t n); /* 0 = ok; auto model-switch */
void   sb_emu_switch_model(sb_emulator *e, int model);                  /* GB_model_t value */
void   sb_emu_set_rewind_length(sb_emulator *e, double seconds);
void   sb_emu_set_turbo(sb_emulator *e, int on);                        /* GB_set_turbo_mode(on, false) */
int    sb_emu_rewind_pop(sb_emulator *e);                               /* 1 = popped, 0 = history empty */
int    sb_emu_battery_dirty(sb_emulator *e);                            /* emu thread / parked only */
void   sb_emu_clear_battery_dirty(sb_emulator *e);

/* Reads ROM title + CRC32 via a throwaway Core init (no session/emulator).
   title must be >= 17 bytes; it is NUL-terminated. Returns 0 on success,
   -1 if len < 0x150 (too small to be a cartridge). */
int sb_rom_info(const uint8_t *rom, size_t len, char *title, uint32_t *crc32);

typedef struct {
    int    color_correction;   /* GB_color_correction_mode_t */
    double light_temperature;  /* -1..1 */
    int    border_mode;        /* GB_border_mode_t */
    int    highpass;           /* GB_highpass_mode_t */
    int    rtc_mode;           /* GB_rtc_mode_t */
    double rewind_seconds;
    double turbo_cap;          /* 0 = uncapped */
    double interference;       /* 0..1 */
} sb_settings;
void sb_emu_apply_settings(sb_emulator *e, const sb_settings *s);
void sb_emu_set_volume_ptr(sb_emulator *e, const atomic_int *volume);  /* 256 = 1.0; NULL = full */

#define SB_AUDIO_SAMPLE_RATE 48000
