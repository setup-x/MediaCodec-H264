package com.mediakit.h264decoder;

import android.util.Log;
import android.view.Surface;

/**
 * H264 单帧 JNI 解码器。
 *
 * 使用方式：
 * <pre>
 *   H264JniDecoder decoder = new H264JniDecoder();
 *   decoder.setSurface(surface);
 *   decoder.init(1920, 1080);
 *   // 逐帧解码
 *   decoder.decodeFrame(frameData, frameData.length);
 *   // 释放
 *   decoder.release();
 * </pre>
 */
public class H264JniDecoder {

    private static final String TAG = "H264JniDecoder";

    // 错误码，与 C++ DecodeError 枚举对应
    public static final int OK                  =  0;
    public static final int NOT_INITIALIZED     = -1;
    public static final int NO_SURFACE          = -2;
    public static final int CODEC_CREATE_FAILED = -3;
    public static final int CODEC_CONFIG_FAILED = -4;
    public static final int INPUT_TIMEOUT       = -5;
    public static final int OUTPUT_TIMEOUT      = -6;
    public static final int INVALID_DATA        = -7;
    public static final int CODEC_ERROR         = -8;

    static {
        System.loadLibrary("h264framedecoder");
    }

    private long handle = 0;

    public H264JniDecoder() {
        handle = nativeCreate();
    }

    /**
     * 绑定渲染目标 Surface，必须在 init() 之前调用。
     * @param surface 目标 Surface，传 null 解绑
     */
    public void setSurface(Surface surface) {
        if (handle == 0) return;
        nativeSetSurface(handle, surface);
    }

    /**
     * 初始化硬件解码器。
     * @param width  视频宽度，传 0 使用默认值 1920
     * @param height 视频高度，传 0 使用默认值 1080
     * @return 0 成功，负数为错误码
     */
    public int init(int width, int height) {
        if (handle == 0) return NOT_INITIALIZED;
        return nativeInit(handle, width, height);
    }

    /**
     * 解码单帧 H264 数据（含起始码），同步等待渲染完成。
     * @param data   H264 裸流数据（以 00 00 01 或 00 00 00 01 起始码开头）
     * @param length 有效数据长度
     * @return 0 成功，负数为错误码
     */
    public int decodeFrame(byte[] data, int length) {
        if (handle == 0) return NOT_INITIALIZED;
        if (data == null || length <= 0) return INVALID_DATA;
        return nativeDecodeFrame(handle, data, length);
    }

    /**
     * 重置解码器状态（清空 SPS/PPS 缓存，可重新从 SPS 帧开始解码）。
     */
    public void flush() {
        if (handle == 0) return;
        nativeFlush(handle);
    }

    /**
     * 释放所有 Native 资源，调用后此对象不可再使用。
     */
    public void release() {
        if (handle == 0) return;
        nativeRelease(handle);
        handle = 0;
        Log.d(TAG, "released");
    }

    public boolean isReleased() {
        return handle == 0;
    }

    // ─── Native 方法声明 ──────────────────────────────────────────────────────

    private native long nativeCreate();
    private native void nativeSetSurface(long handle, Surface surface);
    private native int  nativeInit(long handle, int width, int height);
    private native int  nativeDecodeFrame(long handle, byte[] data, int length);
    private native void nativeFlush(long handle);
    private native void nativeRelease(long handle);
}
