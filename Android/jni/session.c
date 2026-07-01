#include "session.h"
#include "render_gles.h"
#include "audio_aaudio.h"
#include <pthread.h>
#include <stdlib.h>

struct sb_session {
    sb_emulator *emu;
    sb_renderer *render;
    sb_audio *audio;
    ANativeWindow *win;
    pthread_t emu_thread;
    volatile int running;
    volatile int paused;
    pthread_mutex_t pause_mtx;
    pthread_cond_t pause_cv;
};

static void *emu_loop(void *arg)
{
    sb_session *s = arg;
    while (s->running) {
        pthread_mutex_lock(&s->pause_mtx);
        while (s->paused && s->running) pthread_cond_wait(&s->pause_cv, &s->pause_mtx);
        pthread_mutex_unlock(&s->pause_mtx);
        if (!s->running) break;
        sb_emu_run_frame(s->emu);   /* blocks on the audio ring => paced */
    }
    return NULL;
}

sb_session *sb_session_create(int model, const uint8_t *rom, size_t rom_len,
                              const uint8_t *sav, size_t sav_len)
{
    sb_emulator *emu = sb_emu_create(model, rom, rom_len, sav, sav_len);
    if (!emu) return NULL;
    sb_session *s = calloc(1, sizeof(*s));
    s->emu = emu;
    pthread_mutex_init(&s->pause_mtx, NULL);
    pthread_cond_init(&s->pause_cv, NULL);
    return s;
}

void sb_session_set_boot_rom(sb_session *s, int type, const uint8_t *data, size_t len)
{ sb_emu_set_boot_rom(s->emu, type, data, len); }

void sb_session_reset(sb_session *s) { sb_emu_reset(s->emu); }

void sb_session_start(sb_session *s, ANativeWindow *win)
{
    if (s->running) return;
    s->win = win;
    s->running = 1;
    s->paused = 0;
    s->audio = sb_audio_start(sb_emu_audio_ring(s->emu));
    s->render = sb_render_start(win, s->emu);
    pthread_create(&s->emu_thread, NULL, emu_loop, s);
}

void sb_session_stop(sb_session *s)
{
    if (!s->running) return;
    s->running = 0;
    pthread_mutex_lock(&s->pause_mtx);
    s->paused = 0;
    pthread_cond_broadcast(&s->pause_cv);
    pthread_mutex_unlock(&s->pause_mtx);
    /* unblock the emu thread if it's waiting on a full audio ring */
    sb_ring_flush(sb_emu_audio_ring(s->emu));
    pthread_join(s->emu_thread, NULL);
    sb_render_stop(s->render); s->render = NULL;
    sb_audio_stop(s->audio);   s->audio = NULL;
    if (s->win) { ANativeWindow_release(s->win); s->win = NULL; }
}

void sb_session_pause(sb_session *s, int paused)
{
    pthread_mutex_lock(&s->pause_mtx);
    s->paused = paused;
    pthread_cond_broadcast(&s->pause_cv);
    pthread_mutex_unlock(&s->pause_mtx);
    if (s->audio) sb_audio_set_paused(s->audio, paused);
}

void sb_session_set_key(sb_session *s, int idx, int pressed) { sb_emu_set_key(s->emu, idx, pressed); }

size_t sb_session_save_battery(sb_session *s, uint8_t **out) { return sb_emu_save_battery(s->emu, out); }

void sb_session_destroy(sb_session *s)
{
    if (!s) return;
    if (s->running) sb_session_stop(s);
    sb_emu_destroy(s->emu);
    pthread_mutex_destroy(&s->pause_mtx);
    pthread_cond_destroy(&s->pause_cv);
    free(s);
}
