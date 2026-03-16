#pragma once

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window.h>
#include <cstdint>
#include <vector>
#include <queue>
#include <mutex>
#include <thread>
#include <condition_variable>
#include <atomic>

namespace mediakit {

// 错误码定义
enum class DecodeError : int {
    OK                  =  0,
    NOT_INITIALIZED     = -1,
    NO_SURFACE          = -2,
    CODEC_CREATE_FAILED = -3,
    CODEC_CONFIG_FAILED = -4,
    INPUT_TIMEOUT       = -5,
    OUTPUT_TIMEOUT      = -6,
    INVALID_DATA        = -7,
    CODEC_ERROR         = -8,
};

// NALU 类型
enum class NaluType : uint8_t {
    UNSPECIFIED = 0,
    NON_IDR     = 1,
    IDR         = 5,
    SEI         = 6,
    SPS         = 7,
    PPS         = 8,
};

class H264FrameDecoder {
public:
    H264FrameDecoder();
    ~H264FrameDecoder();

    H264FrameDecoder(const H264FrameDecoder&) = delete;
    H264FrameDecoder& operator=(const H264FrameDecoder&) = delete;

    // 绑定渲染 Surface，必须在 init() 前调用
    void setSurface(ANativeWindow* window);

    // 启动解码器及内部线程
    DecodeError init(int width, int height);

    // 投递一帧数据（非阻塞，立即返回）
    DecodeError decodeFrame(const uint8_t* data, size_t length);

    // 清空队列并 flush codec
    void flush();

    // 停止线程并释放所有资源
    void release();

    bool isInitialized() const { return codec_ != nullptr; }

private:
    // ── 内部线程入口 ──────────────────────────────────────────────
    void inputThreadFunc();   // 从 frameQueue_ 取帧 → codec 输入
    void outputThreadFunc();  // codec 输出 → 渲染到 Surface

    // ── NALU 工具 ─────────────────────────────────────────────────
    static const uint8_t* findNaluStart(const uint8_t* data, size_t len, int* startCodeLen);
    static NaluType parseNaluType(const uint8_t* naluData);

    // 构建首帧（SPS + PPS + IDR 合并）并入队
    void enqueueFirstFrame(const uint8_t* idrData, size_t idrLen);

private:
    AMediaCodec*   codec_  = nullptr;
    ANativeWindow* window_ = nullptr;
    AMediaFormat*  format_ = nullptr;

    // SPS/PPS 缓存（含起始码）
    std::vector<uint8_t> spsBuffer_;
    std::vector<uint8_t> ppsBuffer_;
    bool firstFrameSent_ = false;

    // ── 帧队列（Java → input线程）────────────────────────────────
    static constexpr size_t MAX_QUEUE_SIZE = 60;
    std::queue<std::vector<uint8_t>> frameQueue_;
    std::mutex               queueMutex_;
    std::condition_variable  queueCv_;

    // ── 工作线程 ──────────────────────────────────────────────────
    std::thread      inputThread_;
    std::thread      outputThread_;
    std::atomic_bool running_{false};

    // ── PTS ───────────────────────────────────────────────────────
    std::atomic<int64_t> ptsUs_{0};

    static constexpr int64_t INPUT_TIMEOUT_US  = 10'000;   // 10ms
    static constexpr int64_t OUTPUT_TIMEOUT_US = 10'000;   // 10ms
    static constexpr int64_t PTS_STEP_US       = 33'333;   // ~30fps
};

} // namespace mediakit
