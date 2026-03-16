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

extern "C" {

// 创建 Native 解码器实例，返回句柄
JNIEXPORT jlong JNICALL
Java_com_mediakit_h264decoder_H264JniDecoder_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    auto* decoder = new H264FrameDecoder();
    return reinterpret_cast<jlong>(decoder);
}

// 绑定 Surface（ANativeWindow）
JNIEXPORT void JNICALL
Java_com_mediakit_h264decoder_H264JniDecoder_nativeSetSurface(JNIEnv* env, jobject /*thiz*/,
                                                               jlong handle, jobject surface) {
    if (handle == 0) {
        LOGE("nativeSetSurface: invalid handle");
        return;
    }
    ANativeWindow* window = nullptr;
    if (surface != nullptr) {
        window = ANativeWindow_fromSurface(env, surface);
    }
    toDecoder(handle)->setSurface(window);
    if (window != nullptr) {
        // setSurface 内部已 acquire，这里释放 fromSurface 持有的引用
        ANativeWindow_release(window);
    }
}

// 初始化解码器
JNIEXPORT jint JNICALL
Java_com_mediakit_h264decoder_H264JniDecoder_nativeInit(JNIEnv* /*env*/, jobject /*thiz*/,
                                                         jlong handle, jint width, jint height) {
    if (handle == 0) {
        LOGE("nativeInit: invalid handle");
        return static_cast<jint>(DecodeError::NOT_INITIALIZED);
    }
    DecodeError err = toDecoder(handle)->init(width, height);
    return static_cast<jint>(err);
}

// 解码单帧
JNIEXPORT jint JNICALL
Java_com_mediakit_h264decoder_H264JniDecoder_nativeDecodeFrame(JNIEnv* env, jobject /*thiz*/,
                                                                jlong handle,
                                                                jbyteArray data, jint length) {
    if (handle == 0 || data == nullptr || length <= 0) {
        LOGE("nativeDecodeFrame: invalid args");
        return static_cast<jint>(DecodeError::INVALID_DATA);
    }

    jboolean isCopy = JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(data, &isCopy);
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

// Flush 重置
JNIEXPORT void JNICALL
Java_com_mediakit_h264decoder_H264JniDecoder_nativeFlush(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jlong handle) {
    if (handle == 0) {
        LOGE("nativeFlush: invalid handle");
        return;
    }
    toDecoder(handle)->flush();
}

// 释放所有资源
JNIEXPORT void JNICALL
Java_com_mediakit_h264decoder_H264JniDecoder_nativeRelease(JNIEnv* /*env*/, jobject /*thiz*/,
                                                            jlong handle) {
    if (handle == 0) {
        LOGE("nativeRelease: invalid handle");
        return;
    }
    H264FrameDecoder* decoder = toDecoder(handle);
    decoder->release();
    delete decoder;
}

} // extern "C"
