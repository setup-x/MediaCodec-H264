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

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class H264Decoder {

    private static final String MIME_TYPE = "video/avc";
    private static final int TIMEOUT_US = 200 * 1000 ;

    private static final String TAG = "H264Decoder";
    private MediaCodec mediaCodec;
    private Surface surface;
    private HandlerThread decoderThread;
    private Handler decoderHandler;
    private Context context;
    private boolean isVideoSizeSet = false;
    private DecoderCallback callback;
    private volatile boolean dequeueRunning;

    private VideoFramePull videoFramePull;
    private int videoWidth;
    private int videoHeight;

    private int frameIn = 0;
    private int frameOut = 0;


    public H264Decoder(Context context, Surface surface, DecoderCallback callback) {
        this.callback = callback;
        this.surface = surface;
        this.context = context;
    }

    public void startDecoding(final String filePath) {
        decoderThread = new HandlerThread("DecoderThread");
        decoderThread.start();
        decoderHandler = new Handler(decoderThread.getLooper());

        decoderHandler.post(new Runnable() {
            @RequiresApi(api = Build.VERSION_CODES.Q)
            @Override
            public void run() {
                try {
                    initDecoder();
                    decodeRawH264Stream(filePath);
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    isVideoSizeSet = false;
                }
            }
        });
    }


    public void stop() {
        dequeueRunning = false;

        try {
            if (videoFramePull != null) {
                videoFramePull.join(500);
            }

            if (decoderThread != null) {
                decoderThread.quitSafely();
                decoderHandler = null;
            }

            if (mediaCodec != null) {
                mediaCodec.stop();
                mediaCodec.release();
            }

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            videoFramePull = null;
            decoderThread = null;
            mediaCodec = null;
        }
        Log.d(TAG, "stop. ");
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void initDecoder() throws IOException {
        int width = 1920;
        int height = 1080;

        // 创建解码器
        mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE);

        // 创建并配置媒体格式
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setInteger("low-latency", 1);
        // 配置解码器
        mediaCodec.configure(format, surface, null, 0);

        // 启动解码器
        mediaCodec.start();

        // 启动视频帧提取线程
        videoFramePull = new VideoFramePull();
        videoFramePull.start();
    }


    private void decodeRawH264Stream(String filePath) throws IOException {
        try {
            byte[] bytes = getBytes(filePath);
            int startIndex = 0;
            int totalSize = bytes.length;

            while (dequeueRunning) {
                if (totalSize == 0 || startIndex >= totalSize) break;

                int nextFrameStart = findByFrame(bytes, startIndex + 1, totalSize);
                if (nextFrameStart == -1) break;

                int inIndex = mediaCodec.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    // 使用 getInputBuffer(index) 替代已废弃的 getInputBuffers()
                    ByteBuffer byteBuffer = mediaCodec.getInputBuffer(inIndex);
                    if (byteBuffer != null) {
                        byteBuffer.clear();
                        byteBuffer.put(bytes, startIndex, nextFrameStart - startIndex);
                        mediaCodec.queueInputBuffer(inIndex, 0, nextFrameStart - startIndex, 0, 0);
                        Log.d(TAG, "queueInput frame=" + frameIn++);
                    }
                } else {
                    Log.d(TAG, "dequeueInputBuffer timeout");
                    Thread.sleep(10);
                }

                Thread.sleep(30);
                startIndex = nextFrameStart;
            }

            if (videoFramePull != null) {
                dequeueRunning = false;
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }


    //读取一帧数据
    private int findByFrame(byte[] bytes, int start, int totalSize) {
        for (int i = start; i < totalSize - 4; i++) {
            //对output.h264文件分析 可通过分隔符 0x00000001 读取真正的数据
            if ((bytes[i] == 0x00 && bytes[i + 1] == 0x00 && bytes[i + 2] == 0x00 && bytes[i + 3] == 0x01) || (bytes[i] == 0x00 && bytes[i + 1] == 0x00 && bytes[i + 2] == 0x01)) {
                return i;
            }
        }
        return -1;
    }



    private byte[] getBytes(String videoPath) throws IOException {
        AssetManager assetManager = context.getAssets();
        InputStream is = assetManager.open(videoPath, AssetManager.ACCESS_STREAMING);
        int len;
        int size = 1024;
        byte[] buf;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        buf = new byte[size];
        while ((len = is.read(buf, 0, size)) != -1) {
            bos.write(buf, 0, len);
            /*if (bos.size() > 50000000) {
                break;
            }*/
        }
        buf = bos.toByteArray();
        return buf;
    }


    public interface DecoderCallback {
        void onVideoSizeChanged(int width, int height);
    }

    public class VideoFramePull extends Thread {
        @Override
        public void run() {
            dequeueRunning = true;
            while (dequeueRunning && mediaCodec != null) {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                try {
                    int outIndex = mediaCodec.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        Log.d(TAG, "dequeueOutput timeout. ");
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "MediaCodec.INFO_OUTPUT_FORMAT_CHANGED. ");
                    } else if (outIndex >= 0) {
                        if (mediaCodec != null) {
                            if (!isVideoSizeSet) {
                                //获取视频的实际宽度和高度
                                MediaFormat outputFormat = mediaCodec.getOutputFormat();
                                videoWidth = outputFormat.getInteger(MediaFormat.KEY_WIDTH);
                                videoHeight = outputFormat.getInteger(MediaFormat.KEY_HEIGHT);
                                Log.d("xzc", "format: " + outputFormat + "\nvideoWidth: " + videoWidth + ",videoHeight: " + videoHeight);
                                Log.d("xzc", "YUV size: " + info.size);
                                if (callback != null) {
                                    callback.onVideoSizeChanged(videoWidth, videoHeight);
                                }
                                isVideoSizeSet = true;
                            }
                            Log.d(TAG, "dequeueOutputBuffer: " + frameOut++);
                            mediaCodec.releaseOutputBuffer(outIndex, true);

                        }
                    } else {
                        Log.d("xzc", "outIndex < 0.");

                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }


}

