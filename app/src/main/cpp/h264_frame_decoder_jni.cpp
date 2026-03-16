#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include "H264FrameDecoder.h"

#define TAG "H264FrameDecoderJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using mediakit::H264FrameDecoder;
using mediakit::DecodeError;

static inline H264FrameDecoder* toDecoder(jlong handle) {
    return reinterpret_cast<H264FrameDecoder*>(handle);
}

// ── Native 方法实现（去掉 Java_ 前缀命名约定，改为普通 static 函数）────────────

static jlong nativeCreate(JNIEnv * /*env*/, jobject /*thiz*/) {
    auto *decoder = new H264FrameDecoder();
    return reinterpret_cast<jlong>(decoder);
}

static void nativeSetSurface(JNIEnv *env, jobject /*thiz*/, jlong handle, jobject surface) {
    if (handle == 0) {
        LOGE("nativeSetSurface: invalid handle");
        return;
    }
    ANativeWindow *window = nullptr;
    if (surface != nullptr) {
        window = ANativeWindow_fromSurface(env, surface);
    }
    toDecoder(handle)->setSurface(window);
    if (window != nullptr) {
        // setSurface 内部已 acquire，这里释放 fromSurface 持有的引用
        ANativeWindow_release(window);
    }
}

static jint nativeInit(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jint width, jint height) {
    if (handle == 0) {
        LOGE("nativeInit: invalid handle");
        return static_cast<jint>(DecodeError::NOT_INITIALIZED);
    }
    return static_cast<jint>(toDecoder(handle)->init(width, height));
}

static jint nativeDecodeFrame(JNIEnv *env, jobject /*thiz*/,
                              jlong handle, jbyteArray data, jint length) {
    if (handle == 0 || data == nullptr || length <= 0) {
        LOGE("nativeDecodeFrame: invalid args");
        return static_cast<jint>(DecodeError::INVALID_DATA);
    }
    jboolean isCopy = JNI_FALSE;
    jbyte *bytes = env->GetByteArrayElements(data, &isCopy);
    if (bytes == nullptr) {
        LOGE("nativeDecodeFrame: GetByteArrayElements failed");
        return static_cast<jint>(DecodeError::INVALID_DATA);
    }
    DecodeError err = toDecoder(handle)->decodeFrame(
            reinterpret_cast<const uint8_t*>(bytes),
            static_cast<size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return static_cast<jint>(err);
}

static void nativeFlush(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) {
        LOGE("nativeFlush: invalid handle");
        return;
    }
    toDecoder(handle)->flush();
}

static void nativeRelease(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) {
        LOGE("nativeRelease: invalid handle");
        return;
    }
    H264FrameDecoder *decoder = toDecoder(handle);
    decoder->release();
    delete decoder;
}

// ── 动态注册表 ────────────────────────────────────────────────────────────────

static const JNINativeMethod kMethods[] = {
        {"nativeCreate",      "()J",                        reinterpret_cast<void *>(nativeCreate)},
        {"nativeSetSurface",  "(JLandroid/view/Surface;)V", reinterpret_cast<void *>(nativeSetSurface)},
        {"nativeInit",        "(JII)I",                     reinterpret_cast<void *>(nativeInit)},
        {"nativeDecodeFrame", "(J[BI)I",                    reinterpret_cast<void *>(nativeDecodeFrame)},
        {"nativeFlush",       "(J)V",                       reinterpret_cast<void *>(nativeFlush)},
        {"nativeRelease",     "(J)V",                       reinterpret_cast<void *>(nativeRelease)},
};

// ── JNI_OnLoad：库加载时自动调用，完成动态注册 ───────────────────────────────

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("com/mediakit/h264decoder/H264JniDecoder");
    if (clazz == nullptr) {
        LOGE("JNI_OnLoad: FindClass failed");
        return JNI_ERR;
    }

    if (env->RegisterNatives(clazz, kMethods,
                             static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0]))) != 0) {
        LOGE("JNI_OnLoad: RegisterNatives failed");
        return JNI_ERR;
    }

    LOGE("JNI_OnLoad: registered %zu methods", sizeof(kMethods) / sizeof(kMethods[0]));
    return JNI_VERSION_1_6;
}
