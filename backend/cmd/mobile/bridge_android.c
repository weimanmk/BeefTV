#include <jni.h>
#include <stdlib.h>

extern char *StartMobile(char *dataDir);

JNIEXPORT jstring JNICALL Java_com_weimanmk_beeftv_MainActivity_startBackend(
    JNIEnv *env, jclass clazz, jstring path) {
    (void)clazz;
    const char *directory = (*env)->GetStringUTFChars(env, path, NULL);
    if (directory == NULL) return NULL;
    char *result = StartMobile((char *)directory);
    (*env)->ReleaseStringUTFChars(env, path, directory);
    jstring value = (*env)->NewStringUTF(env, result);
    free(result);
    return value;
}
