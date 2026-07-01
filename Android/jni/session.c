#include "session.h"
#include "render_gles.h"
#include "audio_aaudio.h"
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdlib.h>
#include <time.h>
#include <android/log.h>

struct sb_session {
    sb_emulator *emu;
    sb_renderer *render;
    sb_audio *audio;
    ANativeWindow *win;
    pthread_t emu_thread;
    atomic_bool running;
    int paused;                 /* protected by pause_mtx */
    atomic_bool turbo;          /* consumed by emu loop */
    atomic_bool rewinding;      /* consumed by emu loop */
    atomic_bool audio_drop;     /* read by audio_cb via sb_emu_set_audio_drop */
    atomic_bool battery_dirty;  /* published by emu loop, read by JNI */
    int parked;                 /* emu thread is waiting in pause_cv (under pause_mtx) */
    pthread_mutex_t pause_mtx;
    pthread_cond_t pause_cv;
    pthread_cond_t parked_cv;   /* signalled when the emu thread parks */
};

static void *emu_loop(void *arg)
{
    sb_session *s = arg;
    int applied_turbo = 0;
    while (atomic_load(&s->running)) {
        pthread_mutex_lock(&s->pause_mtx);
        while (s->paused && atomic_load(&s->running)) {
            s->parked = 1;
            pthread_cond_broadcast(&s->parked_cv);
            pthread_cond_wait(&s->pause_cv, &s->pause_mtx);
        }
        s->parked = 0;
        pthread_mutex_unlock(&s->pause_mtx);
        if (!atomic_load(&s->running)) break;

        int turbo = atomic_load(&s->turbo) ? 1 : 0;
        if (turbo != applied_turbo) {
            sb_emu_set_turbo(s->emu, turbo);
            applied_turbo = turbo;
        }

        if (atomic_load(&s->rewinding)) {
            /* pop 2 → run 1: net one frame backwards per iteration (iOS shape).
               On empty history, hold position — never creep forward under the
               user's finger. */
            sb_emu_rewind_pop(s->emu);
            if (!sb_emu_rewind_pop(s->emu)) {
                struct timespec ts = {0, 16600000};
                nanosleep(&ts, NULL);
                continue;
            }
        }

        sb_emu_run_frame(s->emu);   /* blocks on the audio ring => paced */
        atomic_store(&s->battery_dirty, sb_emu_battery_dirty(s->emu) != 0);
        if (!s->audio) {
            sb_ring_flush(sb_emu_audio_ring(s->emu));
            struct timespec ts = {0, 16600000};
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
    atomic_init(&s->running, false);
    atomic_init(&s->turbo, false);
    atomic_init(&s->rewinding, false);
    atomic_init(&s->audio_drop, false);
    atomic_init(&s->battery_dirty, false);
    sb_emu_set_audio_drop(emu, &s->audio_drop);
    pthread_mutex_init(&s->pause_mtx, NULL);
    pthread_cond_init(&s->pause_cv, NULL);
    pthread_cond_init(&s->parked_cv, NULL);
    return s;
}

void sb_session_set_boot_rom(sb_session *s, int type, const uint8_t *data, size_t len)
{ if (s) sb_emu_set_boot_rom(s->emu, type, data, len); }

/* Park the emu thread for a blocking op; restore the previous pause state after.
   Nested-safe with an outer explicit pause (menu open): if already paused, the
   op runs parked and park_end leaves it paused. */
static int park_begin(sb_session *s)
{
    pthread_mutex_lock(&s->pause_mtx);
    int was = s->paused;
    pthread_mutex_unlock(&s->pause_mtx);
    if (!was) sb_session_pause(s, 1);
    return was;
}

static void park_end(sb_session *s, int was_paused)
{
    if (!was_paused) sb_session_pause(s, 0);
}

void sb_session_reset(sb_session *s)
{
    if (!s) return;
    int was = park_begin(s);
    sb_emu_reset(s->emu);
    park_end(s, was);
}

size_t sb_session_save_battery(sb_session *s, uint8_t **out)
{
    if (!s) return 0;
    int was = park_begin(s);
    size_t n = sb_emu_save_battery(s->emu, out);
    park_end(s, was);
    return n;
}

void sb_session_set_turbo(sb_session *s, int on)
{
    if (!s) return;
    if (on) {
        atomic_store(&s->audio_drop, true);
        sb_ring_flush(sb_emu_audio_ring(s->emu));   /* unblock producer + drop latency (SDL clears its queue too) */
        atomic_store(&s->turbo, true);
    }
    else {
        atomic_store(&s->turbo, false);
        atomic_store(&s->audio_drop, false);
    }
}

void sb_session_set_rewinding(sb_session *s, int on)
{
    if (!s) return;
    atomic_store(&s->rewinding, on != 0);
}

int sb_session_battery_dirty(sb_session *s)
{
    return s ? (atomic_load(&s->battery_dirty) ? 1 : 0) : 0;
}

void sb_session_clear_battery_dirty(sb_session *s)
{
    if (!s) return;
    int was = park_begin(s);
    sb_emu_clear_battery_dirty(s->emu);
    atomic_store(&s->battery_dirty, false);
    park_end(s, was);
}

size_t sb_session_save_state(sb_session *s, uint8_t **out)
{
    if (!s) return 0;
    int was = park_begin(s);
    size_t n = sb_emu_save_state(s->emu, out);
    park_end(s, was);
    return n;
}

int sb_session_load_state(sb_session *s, const uint8_t *buf, size_t n)
{
    if (!s) return -1;
    int was = park_begin(s);
    int ret = sb_emu_load_state(s->emu, buf, n);
    park_end(s, was);
    return ret;
}

void sb_session_switch_model(sb_session *s, int model)
{
    if (!s) return;
    int was = park_begin(s);
    sb_emu_switch_model(s->emu, model);
    park_end(s, was);
}

void sb_session_copy_frame(sb_session *s, uint32_t *dst, unsigned *w, unsigned *h)
{
    if (!s) { *w = *h = 0; return; }
    sb_emu_copy_front(s->emu, dst, w, h);   /* fb_mtx-protected copy */
}

void sb_session_start(sb_session *s, ANativeWindow *win)
{
    if (!s || atomic_load(&s->running)) {
        if (win) ANativeWindow_release(win);
        return;
    }
    s->win = win;
    atomic_store(&s->running, true);
    s->paused = 0;
    s->audio = sb_audio_start(sb_emu_audio_ring(s->emu));
    if (!s->audio) {
        __android_log_print(ANDROID_LOG_WARN, "SameBoy",
                            "audio start failed; continuing without audio");
    }
    s->render = sb_render_start(win, s->emu);
    if (pthread_create(&s->emu_thread, NULL, emu_loop, s) != 0) {
        atomic_store(&s->running, false);
        sb_render_stop(s->render); s->render = NULL;
        sb_audio_stop(s->audio);   s->audio = NULL;
        if (win) ANativeWindow_release(win);
        s->win = NULL;
        return;
    }
}

void sb_session_stop(sb_session *s)
{
    if (!s || !atomic_load(&s->running)) return;
    atomic_store(&s->running, false);
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
        while (!s->parked && atomic_load(&s->running))
            pthread_cond_wait(&s->parked_cv, &s->pause_mtx);
        pthread_mutex_unlock(&s->pause_mtx);
    }
    pthread_mutex_lock(&s->pause_mtx);
    if (s->audio) sb_audio_set_paused(s->audio, paused);   /* under pause_mtx vs. stop */
    pthread_mutex_unlock(&s->pause_mtx);
}

void sb_session_set_key(sb_session *s, int idx, int pressed) { if (s) sb_emu_set_key(s->emu, idx, pressed); }

void sb_session_destroy(sb_session *s)
{
    if (!s) return;
    if (atomic_load(&s->running)) sb_session_stop(s);
    sb_emu_destroy(s->emu);
    pthread_mutex_destroy(&s->pause_mtx);
    pthread_cond_destroy(&s->pause_cv);
    pthread_cond_destroy(&s->parked_cv);
    free(s);
}
