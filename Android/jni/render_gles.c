#include "render_gles.h"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SameBoyGL", __VA_ARGS__)

#define SB_FB_MAX (256 * 224)

struct sb_renderer {
    ANativeWindow *win;
    sb_emulator *emu;
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

static GLuint compile(GLenum t, const char *src)
{
    GLuint s = glCreateShader(t);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    return s;
}

static void *render_thread(void *arg)
{
    sb_renderer *r = arg;

    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(dpy, NULL, NULL);
    const EGLint cfg_attr[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE
    };
    EGLConfig cfg; EGLint n;
    eglChooseConfig(dpy, cfg_attr, &cfg, 1, &n);
    EGLSurface surf = eglCreateWindowSurface(dpy, cfg, r->win, NULL);
    const EGLint ctx_attr[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctx_attr);
    if (!eglMakeCurrent(dpy, surf, surf, ctx)) { LOGE("eglMakeCurrent failed"); return NULL; }

    GLuint prog = glCreateProgram();
    glAttachShader(prog, compile(GL_VERTEX_SHADER, VS));
    glAttachShader(prog, compile(GL_FRAGMENT_SHADER, FS));
    glBindAttribLocation(prog, 0, "aPos");
    glBindAttribLocation(prog, 1, "aTex");
    glLinkProgram(prog);
    glUseProgram(prog);

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

        /* aspect-correct integer letterbox */
        int scale = 1;
        int sx = (int)(sw / w), sy = (int)(sh / h);
        scale = sx < sy ? sx : sy;
        if (scale < 1) scale = 1;
        int vw = (int)w * scale, vh = (int)h * scale;
        glViewport((sw - vw) / 2, (sh - vh) / 2, vw, vh);

        const GLfloat quad[] = { -1,-1, 1,-1, -1,1, 1,1 };
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, quad);

        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        eglSwapBuffers(dpy, surf);   /* paces to display vsync */
    }

    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(dpy, surf);
    eglDestroyContext(dpy, ctx);
    eglTerminate(dpy);
    return NULL;
}

sb_renderer *sb_render_start(ANativeWindow *win, sb_emulator *emu)
{
    sb_renderer *r = calloc(1, sizeof(*r));
    r->win = win; r->emu = emu; r->running = 1;
    pthread_create(&r->thread, NULL, render_thread, r);
    return r;
}

void sb_render_stop(sb_renderer *r)
{
    if (!r) return;
    r->running = 0;
    pthread_join(r->thread, NULL);
    free(r);
}
