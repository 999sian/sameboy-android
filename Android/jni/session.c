#include "session.h"
#include "render_gles.h"
#include "audio_aaudio.h"
#include <pthread.h>
#include <stdlib.h>
#include <time.h>
#include <android/log.h>

struct sb_session {
    sb_emulator *emu;
    sb_renderer *render;
    sb_audio *audio;
    ANativeWindow *win;
    pthread_t emu_thread;
    volatile int running;
    volatile int paused;
    int parked;                 /* emu thread is waiting in pause_cv (under pause_mtx) */
    pthread_mutex_t pause_mtx;
    pthread_cond_t pause_cv;
    pthread_cond_t parked_cv;   /* signalled when the emu thread parks */
};

static void *emu_loop(void *arg)
{
    sb_session *s = arg;
    while (s->running) {
        pthread_mutex_lock(&s->pause_mtx);
        while (s->paused && s->running) {
            s->parked = 1;
            pthread_cond_broadcast(&s->parked_cv);
            pthread_cond_wait(&s->pause_cv, &s->pause_mtx);
        }
        s->parked = 0;
        pthread_mutex_unlock(&s->pause_mtx);
        if (!s->running) break;
        sb_emu_run_frame(s->emu);   /* blocks on the audio ring => paced */
        if (!s->audio) {
            sb_ring_flush(sb_emu_audio_ring(s->emu));   /* no consumer: discard, don't block */
            struct timespec ts = {0, 16600000};          /* ~1/60 s */
            nanosleep(&ts, NULL);
        }
    }
    return NULL;
}

sb_session *sb_session_create(int model, const uint8_t *rom, size_t rom_len,
                              const uint8_t *sav, size_t sav_len)
{
    sb_emulator *emu = sb_emu_create(model, rom, rom_len, sav, sav_len);
    if (!emu) return NULL;
    sb_session *s = calloc(1, sizeof(*s));
    if (!s) { sb_emu_destroy(emu); return NULL; }
    s->emu = emu;
    pthread_mutex_init(&s->pause_mtx, NULL);
    pthread_cond_init(&s->pause_cv, NULL);
    pthread_cond_init(&s->parked_cv, NULL);
    return s;
}

void sb_session_set_boot_rom(sb_session *s, int type, const uint8_t *data, size_t len)
{ if (s) sb_emu_set_boot_rom(s->emu, type, data, len); }

void sb_session_reset(sb_session *s) { if (s) sb_emu_reset(s->emu); }

void sb_session_start(sb_session *s, ANativeWindow *win)
{
    if (!s || s->running) {
        if (win) ANativeWindow_release(win);
        return;
    }
    s->win = win;
    s->running = 1;
    s->paused = 0;
    s->audio = sb_audio_start(sb_emu_audio_ring(s->emu));
    if (!s->audio) {
        __android_log_print(ANDROID_LOG_WARN, "SameBoy",
                            "audio start failed; continuing without audio");
    }
    s->render = sb_render_start(win, s->emu);
    if (pthread_create(&s->emu_thread, NULL, emu_loop, s) != 0) {
        s->running = 0;
        sb_render_stop(s->render); s->render = NULL;
        sb_audio_stop(s->audio);   s->audio = NULL;
        if (win) ANativeWindow_release(win);
        s->win = NULL;
        return;
    }
}

void sb_session_stop(sb_session *s)
{
    if (!s || !s->running) return;
    s->running = 0;
    pthread_mutex_lock(&s->pause_mtx);
    s->paused = 0;
    pthread_cond_broadcast(&s->pause_cv);
    pthread_mutex_unlock(&s->pause_mtx);
    /* unblock the emu thread if it's waiting on a full audio ring */
    sb_ring_flush(sb_emu_audio_ring(s->emu));
    pthread_join(s->emu_thread, NULL);
    sb_render_stop(s->render); s->render = NULL;
    pthread_mutex_lock(&s->pause_mtx);
    sb_audio *audio = s->audio;   /* take under pause_mtx so a racing pause can't UAF */
    s->audio = NULL;
    pthread_mutex_unlock(&s->pause_mtx);
    sb_audio_stop(audio);
    if (s->win) { ANativeWindow_release(s->win); s->win = NULL; }
}

void sb_session_pause(sb_session *s, int paused)
{
    if (!s) return;
    pthread_mutex_lock(&s->pause_mtx);
    s->paused = paused;
    pthread_cond_broadcast(&s->pause_cv);
    pthread_mutex_unlock(&s->pause_mtx);
    if (paused) {
        /* Synchronous pause: the caller (battery save) must not observe a
           frame mid-run. Unblock a frame stuck on a full audio ring so it
           can finish, then wait until the emu thread has actually parked. */
        sb_ring_flush(sb_emu_audio_ring(s->emu));
        pthread_mutex_lock(&s->pause_mtx);
        while (!s->parked && s->running)
            pthread_cond_wait(&s->parked_cv, &s->pause_mtx);
        pthread_mutex_unlock(&s->pause_mtx);
    }
    pthread_mutex_lock(&s->pause_mtx);
    if (s->audio) sb_audio_set_paused(s->audio, paused);   /* under pause_mtx vs. stop */
    pthread_mutex_unlock(&s->pause_mtx);
}

void sb_session_set_key(sb_session *s, int idx, int pressed) { if (s) sb_emu_set_key(s->emu, idx, pressed); }

size_t sb_session_save_battery(sb_session *s, uint8_t **out) { if (!s) return 0; return sb_emu_save_battery(s->emu, out); }

void sb_session_destroy(sb_session *s)
{
    if (!s) return;
    if (s->running) sb_session_stop(s);
    sb_emu_destroy(s->emu);
    pthread_mutex_destroy(&s->pause_mtx);
    pthread_cond_destroy(&s->pause_cv);
    pthread_cond_destroy(&s->parked_cv);
    free(s);
}
