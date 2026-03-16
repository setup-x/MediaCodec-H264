cmake_minimum_required(VERSION 3.22.1)
project(h264framedecoder)

add_library(h264framedecoder SHARED
    H264FrameDecoder.cpp
    h264_frame_decoder_jni.cpp
)

target_compile_features(h264framedecoder PRIVATE cxx_std_17)

target_link_libraries(h264framedecoder
    mediandk
    android
    log
)
