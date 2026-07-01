#pragma once
#include <stddef.h>
#include <stdint.h>

typedef struct sb_ring sb_ring;

sb_ring *sb_ring_create(size_t capacity_frames);
void     sb_ring_destroy(sb_ring *r);
void     sb_ring_push(sb_ring *r, int16_t left, int16_t right);
size_t   sb_ring_pop(sb_ring *r, int16_t *dst_interleaved, size_t frames);
void     sb_ring_flush(sb_ring *r);
void     sb_ring_shutdown(sb_ring *r);
