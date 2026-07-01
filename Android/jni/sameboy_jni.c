#include <jni.h>
#include <android/native_window_jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <stdlib.h>
#include <string.h>
#include "session.h"
#include <Core/gb.h>

/* boot rom asset name -> GB_boot_rom_t, matching SDL's table */
static const char *const boot_names[] = {
    [GB_BOOT_ROM_DMG_0] = "dmg0_boot.bin",
    [GB_BOOT_ROM_DMG]   = "dmg_boot.bin",
    [GB_BOOT_ROM_MGB]   = "mgb_boot.bin",
    [GB_BOOT_ROM_SGB]   = "sgb_boot.bin",
    [GB_BOOT_ROM_SGB2]  = "sgb2_boot.bin",
    [GB_BOOT_ROM_CGB_0] = "cgb0_boot.bin",
    [GB_BOOT_ROM_CGB]   = "cgb_boot.bin",
    [GB_BOOT_ROM_CGB_E] = "cgbE_boot.bin",
    [GB_BOOT_ROM_AGB_0] = "agb0_boot.bin",
    [GB_BOOT_ROM_AGB]   = "agb_boot.bin",
};

/* Boot ROMs are tiny; the emulator copies each asset's bytes (sb_emu_set_boot_rom),
   so the temporary buffer is not needed after the call. */
static void load_boot_roms(sb_session *s, AAssetManager *am)
{
    for (int t = 0; t < (int)(sizeof(boot_names) / sizeof(boot_names[0])); t++) {
        if (!boot_names[t]) continue;
        char path[64];
        snprintf(path, sizeof(path), "bootroms/%s", boot_names[t]);
        AAsset *a = AAssetManager_open(am, path, AASSET_MODE_BUFFER);
        if (!a) continue;
        off_t len = AAsset_getLength(a);
        const void *bytes = AAsset_getBuffer(a);
        sb_session_set_boot_rom(s, t, (const uint8_t *)bytes, (size_t)len);  /* emulator copies */
        AAsset_close(a);
    }
}

JNIEXPORT jlong JNICALL
Java_io_sameboy_android_NativeBridge_nativeCreate(JNIEnv *env, jclass c, jint model,
                                                  jbyteArray rom, jbyteArray sav, jobject assets)
{
    (void)c;
    jsize rlen = (*env)->GetArrayLength(env, rom);
    jbyte *rbytes = (*env)->GetByteArrayElements(env, rom, NULL);
    jbyte *sbytes = NULL; jsize slen = 0;
    if (sav) { slen = (*env)->GetArrayLength(env, sav); sbytes = (*env)->GetByteArrayElements(env, sav, NULL); }

    sb_session *s = sb_session_create(model, (const uint8_t *)rbytes, (size_t)rlen,
                                      (const uint8_t *)sbytes, (size_t)slen);

    (*env)->ReleaseByteArrayElements(env, rom, rbytes, JNI_ABORT);
    if (sbytes) (*env)->ReleaseByteArrayElements(env, sav, sbytes, JNI_ABORT);
    if (!s) return 0;

    AAssetManager *am = AAssetManager_fromJava(env, assets);
    if (am) load_boot_roms(s, am);
    sb_session_reset(s);
    return (jlong)(uintptr_t)s;
}

JNIEXPORT void JNICALL
Java_io_sameboy_android_NativeBridge_nativeStart(JNIEnv *env, jclass c, jlong ctx, jobject surface)
{
    (void)c;
    sb_session *s = (sb_session *)(uintptr_t)ctx;
    ANativeWindow *win = ANativeWindow_fromSurface(env, surface);  /* +1 ref; released in stop */
    sb_session_start(s, win);
}

JNIEXPORT void JNICALL
Java_io_sameboy_android_NativeBridge_nativeStop(JNIEnv *env, jclass c, jlong ctx)
{ (void)env; (void)c; sb_session_stop((sb_session *)(uintptr_t)ctx); }

JNIEXPORT void JNICALL
Java_io_sameboy_android_NativeBridge_nativePause(JNIEnv *env, jclass c, jlong ctx, jboolean p)
{ (void)env; (void)c; sb_session_pause((sb_session *)(uintptr_t)ctx, p); }

JNIEXPORT void JNICALL
Java_io_sameboy_android_NativeBridge_nativeSetKey(JNIEnv *env, jclass c, jlong ctx, jint idx, jboolean pressed)
{
    (void)env; (void)c;
    if (idx < 0 || idx >= 8) return;  /* GB_KEY_MAX; Core assert is compiled out under NDEBUG */
    sb_session_set_key((sb_session *)(uintptr_t)ctx, idx, pressed);
}

JNIEXPORT void JNICALL
Java_io_sameboy_android_NativeBridge_nativeReset(JNIEnv *env, jclass c, jlong ctx)
{ (void)env; (void)c; sb_session_reset((sb_session *)(uintptr_t)ctx); }

JNIEXPORT jbyteArray JNICALL
Java_io_sameboy_android_NativeBridge_nativeSaveBattery(JNIEnv *env, jclass c, jlong ctx)
{
    (void)c;
    uint8_t *buf = NULL;
    size_t n = sb_session_save_battery((sb_session *)(uintptr_t)ctx, &buf);
    if (n == 0) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)n);
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)n, (const jbyte *)buf);
    free(buf);
    return arr;
}

JNIEXPORT void JNICALL
Java_io_sameboy_android_NativeBridge_nativeDestroy(JNIEnv *env, jclass c, jlong ctx)
{ (void)env; (void)c; sb_session_destroy((sb_session *)(uintptr_t)ctx); }
