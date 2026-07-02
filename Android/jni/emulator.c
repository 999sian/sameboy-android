#include "emulator.h"
#include <Core/gb.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#define SB_BOOT_ROM_COUNT 10   /* GB_BOOT_ROM_AGB + 1 */

struct sb_emulator {
    GB_gameboy_t gb;
    uint32_t buffers[2][SB_MAX_W * SB_MAX_H];
    int back;                       /* index of the buffer Core renders into */
    unsigned front_w, front_h;
    pthread_mutex_t fb_mtx;
    sb_ring *audio;
    struct { uint8_t *data; size_t len; } boot[SB_BOOT_ROM_COUNT];  /* owned copies */
    const atomic_bool *audio_drop;
    const atomic_int *volume;
};

static uint32_t rgb_encode(GB_gameboy_t *gb, uint8_t r, uint8_t g, uint8_t b)
{
    (void)gb;
    return 0xFF000000u | ((uint32_t)b << 16) | ((uint32_t)g << 8) | (uint32_t)r;
}

static void vblank_cb(GB_gameboy_t *gb, GB_vblank_type_t type)
{
    if (type == GB_VBLANK_TYPE_REPEAT) return;   /* nothing new to show */
    sb_emulator *e = GB_get_user_data(gb);
    pthread_mutex_lock(&e->fb_mtx);
    e->front_w = GB_get_screen_width(gb);
    e->front_h = GB_get_screen_height(gb);
    e->back ^= 1;                                 /* swap */
    GB_set_pixels_output(gb, e->buffers[e->back]);
    pthread_mutex_unlock(&e->fb_mtx);
}

static void audio_cb(GB_gameboy_t *gb, GB_sample_t *sample)
{
    sb_emulator *e = GB_get_user_data(gb);
    int16_t l = sample->left, r = sample->right;
    if (e->volume) {
        int v = atomic_load_explicit(e->volume, memory_order_relaxed);
        if (v != 256) { l = (int16_t)(l * v / 256); r = (int16_t)(r * v / 256); }
    }
    if (e->audio_drop && atomic_load_explicit(e->audio_drop, memory_order_relaxed)) {
        sb_ring_try_push(e->audio, l, r);
    }
    else {
        sb_ring_push(e->audio, l, r);
    }
}

static void boot_rom_cb(GB_gameboy_t *gb, GB_boot_rom_t type)
{
    sb_emulator *e = GB_get_user_data(gb);
    if (type < SB_BOOT_ROM_COUNT && e->boot[type].data) {
        GB_load_boot_rom_from_buffer(gb, e->boot[type].data, e->boot[type].len);
        return;
    }
    /* fallbacks mirroring SDL: CGB_E->CGB, AGB_0->AGB */
    if (type == GB_BOOT_ROM_CGB_E && e->boot[GB_BOOT_ROM_CGB].data) {
        GB_load_boot_rom_from_buffer(gb, e->boot[GB_BOOT_ROM_CGB].data, e->boot[GB_BOOT_ROM_CGB].len);
    }
    else if (type == GB_BOOT_ROM_AGB_0 && e->boot[GB_BOOT_ROM_AGB].data) {
        GB_load_boot_rom_from_buffer(gb, e->boot[GB_BOOT_ROM_AGB].data, e->boot[GB_BOOT_ROM_AGB].len);
    }
    /* else: run boot-ROM-less */
}

sb_emulator *sb_emu_create(int model, const uint8_t *rom, size_t rom_len,
                           const uint8_t *sav, size_t sav_len)
{
    sb_emulator *e = calloc(1, sizeof(*e));
    if (!e) return NULL;
    pthread_mutex_init(&e->fb_mtx, NULL);
    /* ~100 ms ring. sb_ring_push blocks when full, so at runtime the AAudio
       callback's pop rate paces the emulation thread (audio is the master
       clock). Kept small so the emu thread never runs far ahead of playback.
       The host test must therefore drain the ring as it steps frames. */
    e->audio = sb_ring_create(SB_AUDIO_SAMPLE_RATE / 10);
    e->back = 0;
    e->front_w = 160; e->front_h = 144;

    GB_init(&e->gb, (GB_model_t)model);
    GB_set_user_data(&e->gb, e);
    GB_set_boot_rom_load_callback(&e->gb, boot_rom_cb);
    GB_set_vblank_callback(&e->gb, vblank_cb);
    GB_set_rgb_encode_callback(&e->gb, rgb_encode);
    GB_set_pixels_output(&e->gb, e->buffers[e->back]);
    GB_set_sample_rate(&e->gb, SB_AUDIO_SAMPLE_RATE);
    GB_apu_set_sample_callback(&e->gb, audio_cb);

    GB_load_rom_from_buffer(&e->gb, rom, rom_len);
    if (sav && sav_len) GB_load_battery_from_buffer(&e->gb, sav, sav_len);
    GB_set_rewind_length(&e->gb, 120);
    return e;
}

void sb_emu_set_boot_rom(sb_emulator *e, int type, const uint8_t *data, size_t len)
{
    if (type < 0 || type >= SB_BOOT_ROM_COUNT) return;
    free(e->boot[type].data);              /* replace any prior copy */
    e->boot[type].data = NULL;
    e->boot[type].len = 0;
    if (data && len) {
        e->boot[type].data = malloc(len);
        memcpy(e->boot[type].data, data, len);
        e->boot[type].len = len;
    }
}

void sb_emu_reset(sb_emulator *e) { GB_reset(&e->gb); }

void sb_emu_run_frame(sb_emulator *e) { GB_run_frame(&e->gb); }

const uint32_t *sb_emu_front_buffer(sb_emulator *e, unsigned *w, unsigned *h)
{
    pthread_mutex_lock(&e->fb_mtx);
    /* front = the buffer NOT currently being rendered into */
    const uint32_t *fb = e->buffers[e->back ^ 1];
    if (w) *w = e->front_w;
    if (h) *h = e->front_h;
    pthread_mutex_unlock(&e->fb_mtx);
    return fb;
}

void sb_emu_copy_front(sb_emulator *e, uint32_t *dst, unsigned *w, unsigned *h)
{
    pthread_mutex_lock(&e->fb_mtx);
    unsigned fw = e->front_w, fh = e->front_h;
    memcpy(dst, e->buffers[e->back ^ 1], (size_t)fw * fh * sizeof(uint32_t));
    pthread_mutex_unlock(&e->fb_mtx);
    if (w) *w = fw;
    if (h) *h = fh;
}

sb_ring *sb_emu_audio_ring(sb_emulator *e) { return e->audio; }

void sb_emu_set_key(sb_emulator *e, int idx, int pressed)
{
    GB_set_key_state(&e->gb, (GB_key_t)idx, pressed != 0);
}

size_t sb_emu_save_battery(sb_emulator *e, uint8_t **out)
{
    int size = GB_save_battery_size(&e->gb);
    if (size <= 0) { *out = NULL; return 0; }
    uint8_t *buf = malloc(size);
    GB_save_battery_to_buffer(&e->gb, buf, size);
    *out = buf;
    return (size_t)size;
}

void sb_emu_set_audio_drop(sb_emulator *e, const atomic_bool *drop_on_full)
{
    e->audio_drop = drop_on_full;
}

size_t sb_emu_save_state(sb_emulator *e, uint8_t **out)
{
    size_t n = GB_get_save_state_size(&e->gb);
    uint8_t *buf = malloc(n);
    if (!buf) return 0;
    GB_save_state_to_buffer(&e->gb, buf);
    *out = buf;
    return n;
}

int sb_emu_load_state(sb_emulator *e, const uint8_t *buf, size_t n)
{
    GB_model_t model = 0;
    if (GB_get_state_model_from_buffer(buf, n, &model) == 0 &&
        GB_get_model(&e->gb) != model) {
        GB_switch_model_and_reset(&e->gb, model);   /* mirrors iOS loadStateFromFile: */
    }
    return GB_load_state_from_buffer(&e->gb, buf, n);
}

void sb_emu_switch_model(sb_emulator *e, int model)
{
    GB_switch_model_and_reset(&e->gb, (GB_model_t)model);
}

void sb_emu_set_rewind_length(sb_emulator *e, double seconds)
{
    GB_set_rewind_length(&e->gb, seconds);
}

void sb_emu_set_turbo(sb_emulator *e, int on)
{
    GB_set_turbo_mode(&e->gb, on != 0, false);   /* frame skip ON in turbo */
}

int sb_emu_rewind_pop(sb_emulator *e)
{
    return GB_rewind_pop(&e->gb) ? 1 : 0;
}

int sb_emu_battery_dirty(sb_emulator *e)
{
    return GB_get_battery_dirty(&e->gb) ? 1 : 0;
}

void sb_emu_clear_battery_dirty(sb_emulator *e)
{
    GB_clear_battery_dirty(&e->gb);
}

int sb_rom_info(const uint8_t *rom, size_t len, char *title, uint32_t *crc32)
{
    /* NOTE: GB_init/GB_reset touch Core's file-static GB_random seed (Core/random.c),
       which is not atomic. Callers run this on a background scan thread; if a game is
       launched mid-scan, a live emulator thread using GB_random (camera/SGB) technically
       races the seed. Effect is cosmetic (RAM/noise randomization), never a crash, and
       Core is unmodifiable here — documented, out of scope (see M3 review). */
    if (len < 0x150) return -1;
    /* GB_gameboy_t is large and embedded by value elsewhere; heap-allocate it
       rather than risk a worker-thread stack. */
    GB_gameboy_t *gb = malloc(sizeof(GB_gameboy_t));
    if (!gb) return -1;
    GB_init(gb, GB_MODEL_CGB_E);
    GB_load_rom_from_buffer(gb, rom, len);
    GB_get_rom_title(gb, title);
    *crc32 = GB_get_rom_crc32(gb);
    GB_free(gb);
    free(gb);
    return 0;
}

void sb_emu_set_volume_ptr(sb_emulator *e, const atomic_int *volume)
{
    e->volume = volume;
}

void sb_emu_apply_settings(sb_emulator *e, const sb_settings *s)
{
    GB_set_color_correction_mode(&e->gb, (GB_color_correction_mode_t)s->color_correction);
    GB_set_light_temperature(&e->gb, s->light_temperature);
    GB_set_border_mode(&e->gb, (GB_border_mode_t)s->border_mode);
    GB_set_highpass_filter_mode(&e->gb, (GB_highpass_mode_t)s->highpass);
    GB_set_rtc_mode(&e->gb, (GB_rtc_mode_t)s->rtc_mode);
    GB_set_rewind_length(&e->gb, s->rewind_seconds);
    GB_set_turbo_cap(&e->gb, s->turbo_cap);
    GB_set_interference_volume(&e->gb, s->interference);
}

void sb_emu_destroy(sb_emulator *e)
{
    if (!e) return;
    sb_ring_shutdown(e->audio);
    GB_free(&e->gb);
    sb_ring_destroy(e->audio);
    for (int i = 0; i < SB_BOOT_ROM_COUNT; i++) free(e->boot[i].data);
    pthread_mutex_destroy(&e->fb_mtx);
    free(e);
}
