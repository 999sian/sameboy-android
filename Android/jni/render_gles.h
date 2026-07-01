#pragma once
#include <android/native_window.h>
#include "emulator.h"

typedef struct sb_renderer sb_renderer;

sb_renderer *sb_render_start(ANativeWindow *win, sb_emulator *emu);
void         sb_render_stop(sb_renderer *r);
