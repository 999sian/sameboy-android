#include "render_gles.h"
#include "sched_hint.h"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SameBoyGL", __VA_ARGS__)

struct sb_renderer {
    ANativeWindow *win;
    sb_emulator *emu;
    const atomic_int *filter;      /* owner-provided; NULL = always off */
    pthread_t thread;
    volatile int running;
    uint32_t staging[SB_FB_MAX];   /* render-owned copy; filled under fb_mtx */
};

static const char *VS =
    "attribute vec2 aPos;attribute vec2 aTex;varying vec2 vTex;"
    "void main(){vTex=aTex;gl_Position=vec4(aPos,0.0,1.0);}";
static const char *FS =
    "precision mediump float;varying vec2 vTex;uniform sampler2D uTex;"
    "void main(){gl_FragColor=texture2D(uTex,vTex);}";
/* DMG dot matrix: darken the first output pixel of every source pixel along each
   axis, so each GB pixel reads as an LCD cell with a gutter. uSrc = source
   resolution, uGap = one output pixel expressed in source-pixel units (1/scale),
   so the grid is exactly 1 px wide at any scale. No smoothing: at integer scale the
   lines are already pixel-aligned, and a soft gutter of this width falls entirely
   between sample centers and renders invisible (verified numerically, scales 2..8). */
static const char *FS_LCD =
    "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
    "precision highp float;\n"
    "#else\n"
    "precision mediump float;\n"
    "#endif\n"
    "varying vec2 vTex;uniform sampler2D uTex;uniform vec2 uSrc;uniform float uGap;"
    "void main(){"
    "vec2 s=step(vec2(uGap),fract(vTex*uSrc));"
    "gl_FragColor=vec4(texture2D(uTex,vTex).rgb*(0.75+0.25*min(s.x,s.y)),1.0);}";

static GLuint compile(GLenum t, const char *src)
{
    GLuint s = glCreateShader(t);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    return s;
}

static GLuint build(const char *fs)
{
    GLuint p = glCreateProgram();
    glAttachShader(p, compile(GL_VERTEX_SHADER, VS));
    glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs));
    glBindAttribLocation(p, 0, "aPos");
    glBindAttribLocation(p, 1, "aTex");
    glLinkProgram(p);
    return p;
}

static void *render_thread(void *arg)
{
    sb_renderer *r = arg;
    sb_sched_boost_current_thread(-4 /* THREAD_PRIORITY_DISPLAY: bias off little cores */);

    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(dpy, NULL, NULL);
    const EGLint cfg_attr[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE
    };
    EGLConfig cfg; EGLint n;
    if (!eglChooseConfig(dpy, cfg_attr, &cfg, 1, &n) || n == 0) { eglTerminate(dpy); return NULL; }
    EGLSurface surf = eglCreateWindowSurface(dpy, cfg, r->win, NULL);
    const EGLint ctx_attr[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctx_attr);
    if (!eglMakeCurrent(dpy, surf, surf, ctx)) {
        LOGE("eglMakeCurrent failed");
        eglDestroySurface(dpy, surf);
        eglDestroyContext(dpy, ctx);
        eglTerminate(dpy);
        return NULL;
    }

    GLuint prog = build(FS);
    GLuint prog_lcd = build(FS_LCD);
    GLint u_src = glGetUniformLocation(prog_lcd, "uSrc");
    GLint u_gap = glGetUniformLocation(prog_lcd, "uGap");

    GLuint tex; glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    unsigned tex_w = 0, tex_h = 0;
    const GLfloat tc[] = { 0,1, 1,1, 0,0, 1,0 };
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, tc);

    while (r->running) {
        /* Free-running re-sample: post every vsync (eglSwapBuffers gates on the panel), so the
           compositor holds each unique emu frame for a consistent run of refreshes (1 at 60Hz,
           2 at 120Hz) — smoothest motion on a fixed-rate panel. (Posting once per produced
           frame instead quantizes to the vsync grid unevenly → judder on 120Hz.) */
        unsigned w, h;
        sb_emu_copy_front(r->emu, r->staging, &w, &h);

        if (w != tex_w || h != tex_h) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, r->staging);
            tex_w = w; tex_h = h;
        }
        else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, r->staging);
        }

        EGLint sw, sh;
        eglQuerySurface(dpy, surf, EGL_WIDTH, &sw);
        eglQuerySurface(dpy, surf, EGL_HEIGHT, &sh);

        /* aspect-correct letterbox: fractional so fullscreen truly fills the panel.
           Windowed console layouts pass exact 160/144-multiple surfaces, so the
           min-of-ratios is an exact integer there -> bit-identical crisp scaling. */
        float scale = (float)sw / w;
        float syf = (float)sh / h;
        if (syf < scale) scale = syf;
        if (scale < 1.0f) scale = 1.0f;
        int vw = (int)(w * scale + 0.5f), vh = (int)(h * scale + 0.5f);
        glViewport((sw - vw) / 2, (sh - vh) / 2, vw, vh);

        const GLfloat quad[] = { -1,-1, 1,-1, -1,1, 1,1 };
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, quad);

        /* Scale 1 has no spare pixel for a gutter (uGap would be 1.0, darkening every
           pixel uniformly) — fall back to the plain blit. */
        if ((r->filter ? atomic_load(r->filter) : 0) == 1 && scale >= 2.0f) {
            glUseProgram(prog_lcd);
            glUniform2f(u_src, (GLfloat)w, (GLfloat)h);
            glUniform1f(u_gap, 1.0f / (GLfloat)scale);
        }
        else {
            glUseProgram(prog);
        }

        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (!eglSwapBuffers(dpy, surf)) break;   /* surface lost; exit loop to teardown */
    }

    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(dpy, surf);
    eglDestroyContext(dpy, ctx);
    eglTerminate(dpy);
    return NULL;
}

sb_renderer *sb_render_start(ANativeWindow *win, sb_emulator *emu, const atomic_int *filter)
{
    sb_renderer *r = calloc(1, sizeof(*r));
    if (!r) return NULL;
    r->win = win; r->emu = emu; r->filter = filter; r->running = 1;
    if (pthread_create(&r->thread, NULL, render_thread, r) != 0) { free(r); return NULL; }
    return r;
}

void sb_render_stop(sb_renderer *r)
{
    if (!r) return;
    r->running = 0;
    sb_emu_wake(r->emu);   /* unblock a present-on-produce wait so the loop sees running=0 */
    pthread_join(r->thread, NULL);
    free(r);
}
