#include "../ring_buffer.h"
#include <assert.h>
#include <string.h>
#include <pthread.h>
#include <stdio.h>

static void test_push_pop_fifo(void)
{
    sb_ring *r = sb_ring_create(8);
    sb_ring_push(r, 100, -100);
    sb_ring_push(r, 200, -200);
    int16_t out[4] = {0};
    size_t n = sb_ring_pop(r, out, 2);
    assert(n == 2);
    assert(out[0] == 100 && out[1] == -100);
    assert(out[2] == 200 && out[3] == -200);
    sb_ring_destroy(r);
}

static void test_underrun_zero_fill(void)
{
    sb_ring *r = sb_ring_create(8);
    sb_ring_push(r, 7, 7);
    int16_t out[6];
    for (int i = 0; i < 6; i++) out[i] = 0x5a;
    size_t n = sb_ring_pop(r, out, 3);   // only 1 frame available
    assert(n == 1);
    assert(out[0] == 7 && out[1] == 7);
    assert(out[2] == 0 && out[3] == 0 && out[4] == 0 && out[5] == 0); // zero-filled
    sb_ring_destroy(r);
}

struct pc { sb_ring *r; };
static void *producer(void *arg)
{
    sb_ring *r = ((struct pc *)arg)->r;
    for (int i = 0; i < 10000; i++) sb_ring_push(r, (int16_t)i, (int16_t)-i);
    return NULL;
}

static void test_blocking_pacing(void)
{
    sb_ring *r = sb_ring_create(64);   // small: producer must block
    struct pc arg = { r };
    pthread_t t;
    pthread_create(&t, NULL, producer, &arg);
    int16_t out[2];
    for (int i = 0; i < 10000; i++) {
        while (sb_ring_pop(r, out, 1) == 0) {}
        assert(out[0] == (int16_t)i);
    }
    pthread_join(t, NULL);
    sb_ring_destroy(r);
}

int main(void)
{
    test_push_pop_fifo();
    test_underrun_zero_fill();
    test_blocking_pacing();
    printf("ring_buffer: all tests passed\n");
    return 0;
}
