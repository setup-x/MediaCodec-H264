package com.mediakit.h264decoder;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.io.InputStream;

/**
 * 基于 JNI 单帧解码器的高层封装，使用方式与 H264AsynMediaCodec 一致。
 *
 * <pre>
 *   H264JniFrameDecoder decoder = new H264JniFrameDecoder(context, surface);
 *   decoder.startDecoding("MiraTest.h264");
 *   // ...
 *   decoder.stopDecoding();
 * </pre>
 */
public class H264JniFrameDecoder {

    private static final String TAG = "H264JniFrameDecoder";

    private static final int    DEFAULT_WIDTH     = 1920;
    private static final int    DEFAULT_HEIGHT    = 1080;
    private static final long   FRAME_INTERVAL_MS = 33;
    private static final int    READ_BUFFER_SIZE  = 64 * 1024;

    private final Context context;
    private final Surface surface;

    private volatile boolean running = false;
    private Thread decodeThread;

    private H264JniDecoder jniDecoder;

    public H264JniFrameDecoder(Context context, Surface surface) {
        this.context = context;
        this.surface = surface;
    }

    /** 开始从 assets 中循环解码指定 H264 裸流文件。 */
    public void startDecoding(final String assetPath) {
        if (running) return;
        running = true;

        jniDecoder = new H264JniDecoder();
        jniDecoder.setSurface(surface);
        int ret = jniDecoder.init(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        if (ret != H264JniDecoder.OK) {
            Log.e(TAG, "init failed, ret=" + ret);
            running = false;
            return;
        }

        decodeThread = new Thread(() -> feedLoop(assetPath), "H264Jni-Feed-Thread");
        decodeThread.start();
        Log.d(TAG, "startDecoding: " + assetPath);
    }

    /** 停止解码并释放所有资源。 */
    public void stopDecoding() {
        running = false;
        if (decodeThread != null) {
            decodeThread.interrupt();
            try { decodeThread.join(1000); } catch (InterruptedException ignored) {}
            decodeThread = null;
        }
        if (jniDecoder != null) {
            jniDecoder.release();
            jniDecoder = null;
        }
        Log.d(TAG, "stopDecoding done");
    }

    // ─── 内部实现 ─────────────────────────────────────────────────────────────

    private void feedLoop(String assetPath) {
        AssetManager am = context.getAssets();
        int loopCount = 0;
        while (running) {
            Log.d(TAG, "loop #" + loopCount++);
            try (InputStream is = am.open(assetPath, AssetManager.ACCESS_STREAMING)) {
                boolean keyFrameSeen = false;
                byte[] readBuf = new byte[READ_BUFFER_SIZE];
                byte[] pending = new byte[0];
                int bytesRead;

                while (running && (bytesRead = is.read(readBuf)) != -1) {
                    byte[] combined = concat(pending, readBuf, bytesRead);
                    int startIndex = 0;

                    while (running) {
                        int nextStart = findStartCode(combined, startIndex + 3, combined.length);
                        if (nextStart == -1) {
                            pending = slice(combined, startIndex, combined.length);
                            break;
                        }
                        int frameLen = nextStart - startIndex;
                        if (frameLen > 0) {
                            byte[] frame = slice(combined, startIndex, nextStart);
                            keyFrameSeen = dispatchFrame(frame, keyFrameSeen);
                        }
                        startIndex = nextStart;
                    }
                }

                // 最后一帧
                if (pending.length > 0 && running) {
                    dispatchFrame(pending, keyFrameSeen);
                }

                // 一轮结束，flush 准备下一轮
                if (running && jniDecoder != null) {
                    jniDecoder.flush();
                }

            } catch (IOException e) {
                Log.e(TAG, "feedLoop error", e);
                break;
            }
        }
    }

    /**
     * 分发单帧：等到 SPS 关键帧后才开始解码，并按帧率限速。
     * @return 更新后的 keyFrameSeen 状态
     */
    private boolean dispatchFrame(byte[] frame, boolean keyFrameSeen) {
        if (!keyFrameSeen) {
            if (!H264AsynMediaCodec.isAvcKeyFrame(frame)) {
                Log.d(TAG, "skip non-SPS frame before keyframe");
                return false;
            }
            keyFrameSeen = true;
        }

        if (jniDecoder != null) {
            int ret = jniDecoder.decodeFrame(frame, frame.length);
            if (ret != H264JniDecoder.OK) {
                Log.w(TAG, "decodeFrame ret=" + ret);
            }
        }

        try {
            Thread.sleep(FRAME_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return keyFrameSeen;
    }

    // ─── 工具方法 ─────────────────────────────────────────────────────────────

    private static byte[] concat(byte[] a, byte[] b, int bLen) {
        byte[] result = new byte[a.length + bLen];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, bLen);
        return result;
    }

    private static byte[] slice(byte[] src, int from, int to) {
        byte[] result = new byte[to - from];
        System.arraycopy(src, from, result, 0, result.length);
        return result;
    }

    private static int findStartCode(byte[] data, int start, int length) {
        for (int i = start; i < length - 3; i++) {
            if (data[i] == 0x00 && data[i + 1] == 0x00) {
                if (data[i + 2] == 0x01) return i;
                if (data[i + 2] == 0x00 && data[i + 3] == 0x01) return i;
            }
        }
        return -1;
    }
}
