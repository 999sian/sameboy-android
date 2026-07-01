#pragma once
#include "ring_buffer.h"

typedef struct sb_audio sb_audio;

sb_audio *sb_audio_start(sb_ring *ring);
void      sb_audio_set_paused(sb_audio *a, int paused);
void      sb_audio_stop(sb_audio *a);
