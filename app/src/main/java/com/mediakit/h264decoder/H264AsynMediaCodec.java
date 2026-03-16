package com.mediakit.h264decoder;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class H264AsynMediaCodec {

    private static final String TAG = "H264AsynMediaCodec";

    // 默认分辨率，会在 onOutputFormatChanged 中更新
    private static final int DEFAULT_WIDTH = 1920;
    private static final int DEFAULT_HEIGHT = 1080;
    // 默认帧率对应的帧间隔 ms
    private static final long FRAME_INTERVAL_MS = 33;
    // 流式读取缓冲区大小
    private static final int READ_BUFFER_SIZE = 64 * 1024;
    // 最大缓存帧数
    private static final int MAX_QUEUE_SIZE = 60;

    private final Object syncDecoder = new Object();
    private HandlerThread handlerThread;
    private Handler handler;
    private final Surface surface;
    private final Context context;

    private int resolutionW = DEFAULT_WIDTH;
    private int resolutionH = DEFAULT_HEIGHT;

    // volatile 保证多线程可见性
    private volatile boolean dequeueRunning = false;
    private volatile boolean hasReceivedKeyFrame = false;

    private int frameIn = 0;
    private int frameOut = 0;

    // 泛型化队列，避免原始类型警告
    private final ArrayBlockingQueue<byte[]> frameQueue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);

    private MediaCodec mediaCodec;

    private final MediaCodec.Callback callback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {
            // 此回调已在 HandlerThread 上执行，直接非阻塞取帧
            // 用 poll(0) 避免阻塞 HandlerThread 导致回调积压
            byte[] bytes = frameQueue.poll();
            try {
                ByteBuffer inputBuffer = codec.getInputBuffer(index);
                if (inputBuffer == null) return;
                inputBuffer.clear();
                if (bytes != null && bytes.length > 0) {
                    inputBuffer.put(bytes);
                    codec.queueInputBuffer(index, 0, bytes.length, frameIn * FRAME_INTERVAL_MS * 1000, 0);
                    Log.d(TAG, "queueInput frame=" + frameIn++ + " index=" + index);
                } else {
                    // 队列暂时为空，提交空 buffer 让 codec 继续工作
                    codec.queueInputBuffer(index, 0, 0, 0, 0);
                }
            } catch (Exception e) {
                Log.e(TAG, "onInputBufferAvailable error", e);
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            // releaseOutputBuffer 直接在回调线程调用，不需要额外线程
            try {
                Log.d(TAG, "outputFrame=" + frameOut++);
                // render=true 直接渲染到 Surface
                codec.releaseOutputBuffer(index, true);
            } catch (Exception e) {
                Log.e(TAG, "onOutputBufferAvailable error", e);
            }
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.e(TAG, "MediaCodec error: " + e.getDiagnosticInfo(), e);
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            resolutionW = format.getInteger(MediaFormat.KEY_WIDTH);
            resolutionH = format.getInteger(MediaFormat.KEY_HEIGHT);
            Log.d(TAG, "outputFormatChanged: " + resolutionW + "x" + resolutionH);
        }
    };

    public H264AsynMediaCodec(Context context, Surface surface) {
        this.context = context;
        this.surface = surface;
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public void startDecoding(final String filePath) {
        new Thread(() -> {
            try {
                initDecoder();
                dequeueRunning = true;
                feedFrames(filePath);
            } catch (IOException e) {
                Log.e(TAG, "startDecoding error", e);
            }
        }, "H264-Feed-Thread").start();
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void initDecoder() throws IOException {
        synchronized (syncDecoder) {
            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, resolutionW, resolutionH);
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);

            handlerThread = new HandlerThread("MediaCodecCallbackThread");
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());
            mediaCodec.setCallback(callback, handler);
            mediaCodec.configure(format, surface, null, 0);
            Log.d(TAG, "initDecoder done, format=" + format);
        }
    }

    public void stopDecoding() {
        dequeueRunning = false;
        synchronized (syncDecoder) {
            try {
                frameQueue.clear();
                if (mediaCodec != null) {
                    mediaCodec.stop();
                    mediaCodec.setCallback(null);
                    mediaCodec.release();
                    mediaCodec = null;
                }
                if (handlerThread != null) {
                    handlerThread.quitSafely();
                    handlerThread.join(500);
                    handlerThread = null;
                    handler = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "stopDecoding error", e);
            } finally {
                hasReceivedKeyFrame = false;
            }
        }
        Log.d(TAG, "stopDecoding done");
    }

    /**
     * 流式解析 H264 裸流并按帧投入队列，循环播放。
     */
    private void feedFrames(String filePath) {
        AssetManager assetManager = context.getAssets();
        int loopCount = 0;
        while (dequeueRunning) {
            Log.d(TAG, "loop #" + loopCount++);
            try (InputStream is = assetManager.open(filePath, AssetManager.ACCESS_STREAMING)) {
                byte[] readBuf = new byte[READ_BUFFER_SIZE];
                byte[] pending = new byte[0];

                int bytesRead;
                while (dequeueRunning && (bytesRead = is.read(readBuf)) != -1) {
                    byte[] combined = new byte[pending.length + bytesRead];
                    System.arraycopy(pending, 0, combined, 0, pending.length);
                    System.arraycopy(readBuf, 0, combined, pending.length, bytesRead);

                    int startIndex = 0;
                    while (dequeueRunning) {
                        int nextStart = findStartCode(combined, startIndex + 3, combined.length);
                        if (nextStart == -1) {
                            pending = new byte[combined.length - startIndex];
                            System.arraycopy(combined, startIndex, pending, 0, pending.length);
                            break;
                        }
                        int frameLen = nextStart - startIndex;
                        if (frameLen > 0) {
                            byte[] frame = new byte[frameLen];
                            System.arraycopy(combined, startIndex, frame, 0, frameLen);
                            dispatchFrame(frame);
                        }
                        startIndex = nextStart;
                    }
                }

                // 送入最后一帧
                if (pending.length > 0 && dequeueRunning) {
                    dispatchFrame(pending);
                }

                // 一轮播放结束，flush codec 并重置状态准备下一轮
                if (dequeueRunning) {
                    frameQueue.clear();
                    synchronized (syncDecoder) {
                        if (mediaCodec != null) {
                            mediaCodec.flush();
                        }
                    }
                    hasReceivedKeyFrame = false;
                    frameIn = 0;
                    frameOut = 0;
                    Log.d(TAG, "loop end, restarting");
                }

            } catch (IOException e) {
                Log.e(TAG, "feedFrames error", e);
                break;
            }
        }
    }

    /**
     * 将一帧数据放入队列，等待 SPS 关键帧后才启动 codec。
     */
    private void dispatchFrame(byte[] frame) {
        if (!hasReceivedKeyFrame) {
            if (!isAvcKeyFrame(frame)) {
                Log.d(TAG, "skip non-SPS frame before keyframe");
                return;
            }
            // 收到 SPS，启动 codec
            hasReceivedKeyFrame = true;
            synchronized (syncDecoder) {
                if (mediaCodec != null) {
                    mediaCodec.start();
                }
            }
        }

        try {
            // 阻塞等待队列有空位，最多等一帧时间
            if (!frameQueue.offer(frame, FRAME_INTERVAL_MS * 2, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "frameQueue full, drop frame");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 按帧率控制送帧节奏，避免队列瞬间打满
        try {
            Thread.sleep(FRAME_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 查找 H264 起始码 00 00 00 01 或 00 00 01，返回起始位置，找不到返回 -1。
     */
    private static int findStartCode(byte[] data, int start, int length) {
        for (int i = start; i < length - 3; i++) {
            if (data[i] == 0x00 && data[i + 1] == 0x00) {
                if (data[i + 2] == 0x01) return i;           // 3-byte start code
                if (data[i + 2] == 0x00 && data[i + 3] == 0x01) return i; // 4-byte start code
            }
        }
        return -1;
    }

    /**
     * 判断是否为 AVC 关键帧（SPS NALU type=7）。
     */
    public static boolean isAvcKeyFrame(byte[] data) {
        if (data == null || data.length < 5) return false;
        int naluType;
        if (data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x00 && data[3] == 0x01) {
            naluType = data[4] & 0x1F;
        } else if (data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x01) {
            naluType = data[3] & 0x1F;
        } else {
            return false;
        }
        return naluType == 7; // SPS
    }
}
