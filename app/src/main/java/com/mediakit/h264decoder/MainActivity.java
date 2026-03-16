package com.mediakit.h264decoder;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private SurfaceView surfaceView;
    private H264AsynMediaCodec h264AsynMediaCodec;
    private H264Decoder h264Decoder;

    private H264JniFrameDecoder jniFrameDecoder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        surfaceView = findViewById(R.id.surfaceView);
        hideSystemUI();
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                // ── 原有解码器（保留） ──
//                h264AsynMediaCodec = new H264AsynMediaCodec(MainActivity.this, holder.getSurface());
//                h264AsynMediaCodec.startDecoding("MiraTest.h264");

                // ── JNI 单帧解码器示例（注释掉，需要时切换） ──
                jniFrameDecoder = new H264JniFrameDecoder(MainActivity.this, holder.getSurface());
                jniFrameDecoder.startDecoding("MiraTest.h264");
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {}
        });
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (jniFrameDecoder != null) {
            jniFrameDecoder.stopDecoding();
            jniFrameDecoder = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (h264AsynMediaCodec != null) {
            h264AsynMediaCodec.stopDecoding();
        }
        if (h264Decoder != null) {
            h264Decoder.stop();
        }
        if (jniFrameDecoder != null) {
            jniFrameDecoder.stopDecoding();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (h264AsynMediaCodec != null) {
                    h264AsynMediaCodec.stopDecoding();
                }
                if (h264Decoder != null) {
                    h264Decoder.stop();
                }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }
}