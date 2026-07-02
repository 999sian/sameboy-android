#include "link.h"
#include <Core/gb.h>
#include <Core/memory.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <time.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <netdb.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <poll.h>

/* ---------- loopback (host tests): two ends over one mutex+cond byte FIFO ---------- */
#define LB_CAP 4096
typedef struct {
    pthread_mutex_t mtx;
    pthread_cond_t  cv;
    uint8_t q[2][LB_CAP];
    unsigned head[2], tail[2];   /* q[i]: bytes flowing toward end i */
    int refs;
    bool closed;
} lb_shared;

typedef struct { sb_transport base; lb_shared *sh; int idx; } lb_transport;

static bool lb_send(sb_transport *t, uint8_t byte) {
    lb_transport *l = (lb_transport *)t;
    lb_shared *sh = l->sh;
    int dst = l->idx ^ 1;                 /* send toward the other end */
    pthread_mutex_lock(&sh->mtx);
    if (sh->closed) { pthread_mutex_unlock(&sh->mtx); return false; }
    unsigned n = (sh->tail[dst] + 1) % LB_CAP;
    if (n == sh->head[dst]) { pthread_mutex_unlock(&sh->mtx); return false; } /* full */
    sh->q[dst][sh->tail[dst]] = byte;
    sh->tail[dst] = n;
    pthread_cond_broadcast(&sh->cv);
    pthread_mutex_unlock(&sh->mtx);
    return true;
}
static bool lb_recv(sb_transport *t, uint8_t *out, int timeout_ms) {
    lb_transport *l = (lb_transport *)t;
    lb_shared *sh = l->sh;
    int me = l->idx;
    pthread_mutex_lock(&sh->mtx);
    while (sh->head[me] == sh->tail[me] && !sh->closed) {
        if (timeout_ms == 0) { pthread_mutex_unlock(&sh->mtx); return false; }
        struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += timeout_ms / 1000;
        ts.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
        if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
        if (pthread_cond_timedwait(&sh->cv, &sh->mtx, &ts) == ETIMEDOUT) {
            pthread_mutex_unlock(&sh->mtx); return false;
        }
    }
    if (sh->head[me] == sh->tail[me]) { pthread_mutex_unlock(&sh->mtx); return false; } /* closed+empty */
    *out = sh->q[me][sh->head[me]];
    sh->head[me] = (sh->head[me] + 1) % LB_CAP;
    pthread_mutex_unlock(&sh->mtx);
    return true;
}
static void lb_close(sb_transport *t) {
    lb_transport *l = (lb_transport *)t;
    lb_shared *sh = l->sh;
    pthread_mutex_lock(&sh->mtx);
    sh->closed = true;
    pthread_cond_broadcast(&sh->cv);
    int last = (--sh->refs == 0);
    pthread_mutex_unlock(&sh->mtx);
    if (last) { pthread_mutex_destroy(&sh->mtx); pthread_cond_destroy(&sh->cv); free(sh); }
    free(l);
}
sb_transport *sb_transport_loopback_pair(sb_transport **other) {
    lb_shared *sh = calloc(1, sizeof(*sh));
    if (!sh) return NULL;
    pthread_mutex_init(&sh->mtx, NULL);
    pthread_cond_init(&sh->cv, NULL);
    sh->refs = 2;
    lb_transport *a = calloc(1, sizeof(*a)), *b = calloc(1, sizeof(*b));
    if (!a || !b) { free(a); free(b); pthread_mutex_destroy(&sh->mtx); pthread_cond_destroy(&sh->cv); free(sh); return NULL; }
    a->base.send = lb_send; a->base.recv = lb_recv; a->base.close = lb_close; a->sh = sh; a->idx = 0;
    b->base.send = lb_send; b->base.recv = lb_recv; b->base.close = lb_close; b->sh = sh; b->idx = 1;
    *other = &b->base;
    return &a->base;
}

/* ---------- TCP ---------- */
typedef struct { sb_transport base; int fd; } tcp_transport;

static bool tcp_send(sb_transport *t, uint8_t byte) {
    tcp_transport *c = (tcp_transport *)t;
    if (c->fd < 0) return false;
    while (1) {
        ssize_t n = send(c->fd, &byte, 1, MSG_NOSIGNAL);
        if (n == 1) return true;
        if (n < 0 && errno == EINTR) continue;
        return false;
    }
}
static bool tcp_recv(sb_transport *t, uint8_t *out, int timeout_ms) {
    tcp_transport *c = (tcp_transport *)t;
    if (c->fd < 0) return false;
    struct pollfd pfd = { .fd = c->fd, .events = POLLIN };
    int pr = poll(&pfd, 1, timeout_ms);
    if (pr <= 0) return false;                 /* 0 = timeout, <0 = error */
    while (1) {
        ssize_t n = recv(c->fd, out, 1, 0);
        if (n == 1) return true;
        if (n < 0 && errno == EINTR) continue;
        return false;                          /* 0 = peer closed, <0 = error */
    }
}
static void tcp_close(sb_transport *t) {
    tcp_transport *c = (tcp_transport *)t;
    if (c->fd >= 0) { shutdown(c->fd, SHUT_RDWR); close(c->fd); c->fd = -1; }
    free(c);
}
static sb_transport *tcp_wrap(int fd) {
    int one = 1; setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    tcp_transport *c = calloc(1, sizeof(*c));
    if (!c) { close(fd); return NULL; }
    c->base.send = tcp_send; c->base.recv = tcp_recv; c->base.close = tcp_close; c->fd = fd;
    return &c->base;
}
sb_transport *sb_transport_tcp_listen(int port) {
    int ls = socket(AF_INET, SOCK_STREAM, 0);
    if (ls < 0) return NULL;
    int one = 1; setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in a = {0};
    a.sin_family = AF_INET; a.sin_addr.s_addr = htonl(INADDR_ANY); a.sin_port = htons((uint16_t)port);
    if (bind(ls, (struct sockaddr *)&a, sizeof(a)) < 0 || listen(ls, 1) < 0) { close(ls); return NULL; }
    int fd = accept(ls, NULL, NULL);
    close(ls);
    if (fd < 0) return NULL;
    return tcp_wrap(fd);
}
sb_transport *sb_transport_tcp_connect(const char *host, int port) {
    char portstr[16]; snprintf(portstr, sizeof(portstr), "%d", port);
    struct addrinfo hints = {0}, *res = NULL;
    hints.ai_family = AF_INET; hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(host, portstr, &hints, &res) != 0 || !res) return NULL;
    int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (fd < 0) { freeaddrinfo(res); return NULL; }
    if (connect(fd, res->ai_addr, res->ai_addrlen) < 0) { close(fd); freeaddrinfo(res); return NULL; }
    freeaddrinfo(res);
    return tcp_wrap(fd);
}
