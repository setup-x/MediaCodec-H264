#include "H264FrameDecoder.h"
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/log.h>
#include <cstring>

#define TAG "H264FrameDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace mediakit {

H264FrameDecoder::H264FrameDecoder() = default;

H264FrameDecoder::~H264FrameDecoder() {
    release();
}

// ── Surface ───────────────────────────────────────────────────────────────────

void H264FrameDecoder::setSurface(ANativeWindow* window) {
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    if (window != nullptr) {
        ANativeWindow_acquire(window);
        window_ = window;
    }
}

// ── init / release ────────────────────────────────────────────────────────────

DecodeError H264FrameDecoder::init(int width, int height) {
    if (window_ == nullptr) {
        LOGE("init: no surface bound");
        return DecodeError::NO_SURFACE;
    }
    if (width  <= 0) width  = 1920;
    if (height <= 0) height = 1080;

    format_ = AMediaFormat_new();
    AMediaFormat_setString(format_, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format_,  AMEDIAFORMAT_KEY_WIDTH,  width);
    AMediaFormat_setInt32(format_,  AMEDIAFORMAT_KEY_HEIGHT, height);

    codec_ = AMediaCodec_createDecoderByType("video/avc");
    if (codec_ == nullptr) {
        LOGE("init: createDecoderByType failed");
        AMediaFormat_delete(format_); format_ = nullptr;
        return DecodeError::CODEC_CREATE_FAILED;
    }

    media_status_t st = AMediaCodec_configure(codec_, format_, window_, nullptr, 0);
    if (st != AMEDIA_OK) {
        LOGE("init: configure failed, st=%d", st);
        AMediaCodec_delete(codec_); codec_ = nullptr;
        AMediaFormat_delete(format_); format_ = nullptr;
        return DecodeError::CODEC_CONFIG_FAILED;
    }

    st = AMediaCodec_start(codec_);
    if (st != AMEDIA_OK) {
        LOGE("init: start failed, st=%d", st);
        AMediaCodec_delete(codec_); codec_ = nullptr;
        AMediaFormat_delete(format_); format_ = nullptr;
        return DecodeError::CODEC_CONFIG_FAILED;
    }

    running_ = true;
    inputThread_  = std::thread(&H264FrameDecoder::inputThreadFunc,  this);
    outputThread_ = std::thread(&H264FrameDecoder::outputThreadFunc, this);

    LOGI("init: started %dx%d", width, height);
    return DecodeError::OK;
}

void H264FrameDecoder::release() {
    if (!running_.exchange(false)) return;  // 已经释放过

    // 唤醒 input 线程退出
    queueCv_.notify_all();

    if (inputThread_.joinable())  inputThread_.join();
    if (outputThread_.joinable()) outputThread_.join();

    if (codec_ != nullptr) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    if (format_ != nullptr) {
        AMediaFormat_delete(format_);
        format_ = nullptr;
    }
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }

    {
        std::lock_guard<std::mutex> lk(queueMutex_);
        while (!frameQueue_.empty()) frameQueue_.pop();
    }
    spsBuffer_.clear();
    ppsBuffer_.clear();
    firstFrameSent_ = false;
    ptsUs_ = 0;
    LOGI("release: done");
}

void H264FrameDecoder::flush() {
    {
        std::lock_guard<std::mutex> lk(queueMutex_);
        while (!frameQueue_.empty()) frameQueue_.pop();
    }
    if (codec_ != nullptr) {
        AMediaCodec_flush(codec_);
    }
    spsBuffer_.clear();
    ppsBuffer_.clear();
    firstFrameSent_ = false;
    ptsUs_ = 0;
    LOGI("flush: done");
}

// ── decodeFrame（Java 层调用，非阻塞）────────────────────────────────────────

DecodeError H264FrameDecoder::decodeFrame(const uint8_t* data, size_t length) {
    if (codec_ == nullptr || !running_) return DecodeError::NOT_INITIALIZED;
    if (data == nullptr || length == 0)  return DecodeError::INVALID_DATA;

    int startCodeLen = 0;
    const uint8_t* naluData = findNaluStart(data, length, &startCodeLen);
    if (naluData == nullptr) {
        LOGE("decodeFrame: no start code");
        return DecodeError::INVALID_DATA;
    }

    NaluType type = parseNaluType(naluData);

    // SPS / PPS 只缓存，不入队
    if (type == NaluType::SPS) {
        spsBuffer_.assign(data, data + length);
        return DecodeError::OK;
    }
    if (type == NaluType::PPS) {
        ppsBuffer_.assign(data, data + length);
        return DecodeError::OK;
    }

    // IDR 首帧：合并 SPS+PPS+IDR 后入队
    if (type == NaluType::IDR && !firstFrameSent_) {
        enqueueFirstFrame(data, length);
        firstFrameSent_ = true;
        return DecodeError::OK;
    }

    // 普通帧：直接入队
    if (!firstFrameSent_) return DecodeError::OK;  // 还没收到首帧，丢弃

    {
        std::lock_guard<std::mutex> lk(queueMutex_);
        if (frameQueue_.size() >= MAX_QUEUE_SIZE) {
            LOGE("decodeFrame: queue full, drop frame");
            return DecodeError::OK;
        }
        frameQueue_.emplace(data, data + length);
    }
    queueCv_.notify_one();
    return DecodeError::OK;
}

void H264FrameDecoder::enqueueFirstFrame(const uint8_t* idrData, size_t idrLen) {
    std::vector<uint8_t> combined;
    combined.reserve(spsBuffer_.size() + ppsBuffer_.size() + idrLen);
    combined.insert(combined.end(), spsBuffer_.begin(), spsBuffer_.end());
    combined.insert(combined.end(), ppsBuffer_.begin(), ppsBuffer_.end());
    combined.insert(combined.end(), idrData, idrData + idrLen);

    std::lock_guard<std::mutex> lk(queueMutex_);
    frameQueue_.push(std::move(combined));
    queueCv_.notify_one();
}

// ── input 线程：frameQueue_ → codec 输入 buffer ───────────────────────────────

void H264FrameDecoder::inputThreadFunc() {
    LOGI("inputThread: started");
    while (running_) {
        std::vector<uint8_t> frame;
        {
            std::unique_lock<std::mutex> lk(queueMutex_);
            queueCv_.wait(lk, [this] {
                return !frameQueue_.empty() || !running_;
            });
            if (!running_ && frameQueue_.empty()) break;
            frame = std::move(frameQueue_.front());
            frameQueue_.pop();
        }

        // 尝试获取输入 buffer，短超时避免长时间阻塞
        ssize_t bufIdx = AMediaCodec_dequeueInputBuffer(codec_, INPUT_TIMEOUT_US);
        if (bufIdx < 0) {
            // 超时则把帧塞回队头（重新入队头部用 deque 更好，这里简单重入队尾）
            std::lock_guard<std::mutex> lk(queueMutex_);
            frameQueue_.push(std::move(frame));
            continue;
        }

        size_t bufSize = 0;
        uint8_t* buf = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(bufIdx), &bufSize);
        if (buf == nullptr || bufSize < frame.size()) {
            LOGE("inputThread: buffer too small %zu < %zu", bufSize, frame.size());
            AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(bufIdx), 0, 0, 0, 0);
            continue;
        }

        memcpy(buf, frame.data(), frame.size());
        int64_t pts = ptsUs_.fetch_add(PTS_STEP_US);
        AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(bufIdx),
                                     0, frame.size(), pts, 0);
    }
    LOGI("inputThread: exited");
}

// ── output 线程：codec 输出 buffer → 渲染 ────────────────────────────────────

void H264FrameDecoder::outputThreadFunc() {
    LOGI("outputThread: started");
    while (running_) {
        AMediaCodecBufferInfo info{};
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, OUTPUT_TIMEOUT_US);

        if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            continue;  // 超时，继续轮询
        }
        if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat* fmt = AMediaCodec_getOutputFormat(codec_);
            int32_t w = 0, h = 0;
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH,  &w);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &h);
            LOGI("outputThread: format changed %dx%d", w, h);
            AMediaFormat_delete(fmt);
            continue;
        }
        if (outIdx < 0) {
            LOGE("outputThread: unexpected index %zd", outIdx);
            continue;
        }

        // render=true 直接渲染到 Surface
        AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(outIdx), true);
    }
    LOGI("outputThread: exited");
}

// ── NALU 工具 ─────────────────────────────────────────────────────────────────

const uint8_t* H264FrameDecoder::findNaluStart(const uint8_t* data, size_t len, int* startCodeLen) {
    if (data == nullptr || len < 3) return nullptr;
    for (size_t i = 0; i + 2 < len; ++i) {
        if (data[i] == 0x00 && data[i+1] == 0x00) {
            if (i + 3 < len && data[i+2] == 0x00 && data[i+3] == 0x01) {
                if (startCodeLen) *startCodeLen = 4;
                return data + i + 4;
            }
            if (data[i+2] == 0x01) {
                if (startCodeLen) *startCodeLen = 3;
                return data + i + 3;
            }
        }
    }
    return nullptr;
}

NaluType H264FrameDecoder::parseNaluType(const uint8_t* naluData) {
    if (naluData == nullptr) return NaluType::UNSPECIFIED;
    switch (naluData[0] & 0x1F) {
        case 1:  return NaluType::NON_IDR;
        case 5:  return NaluType::IDR;
        case 6:  return NaluType::SEI;
        case 7:  return NaluType::SPS;
        case 8:  return NaluType::PPS;
        default: return NaluType::UNSPECIFIED;
    }
}

} // namespace mediakit
