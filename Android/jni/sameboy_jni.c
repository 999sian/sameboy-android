#include <jni.h>

JNIEXPORT jstring JNICALL
Java_io_sameboy_android_NativeBridge_nativeAbiTag(JNIEnv *env, jclass clazz)
{
    (void)clazz;
#if defined(__aarch64__)
    return (*env)->NewStringUTF(env, "arm64-v8a");
#elif defined(__arm__)
    return (*env)->NewStringUTF(env, "armeabi-v7a");
#elif defined(__x86_64__)
    return (*env)->NewStringUTF(env, "x86_64");
#else
    return (*env)->NewStringUTF(env, "x86");
#endif
}
