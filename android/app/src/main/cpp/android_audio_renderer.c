#include "android_audio_renderer.h"
#include "alac.h"
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>

#define TAG "AndroidAudioRenderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// External JNI hooks
extern void jni_on_audio_frame(const uint8_t *data, int length, int compression_type, uint64_t ntp_time, unsigned short seqnum);
extern void jni_on_volume_changed(float volume);
extern void jni_on_audio_state_changed(bool is_active);

static bool s_is_running = false;
static unsigned char s_current_compression_type = 2; // Default to ALAC for AirPlay
static alac_file *s_alac = NULL;
static uint8_t s_pcm_out[65536]; // Generous 64KB PCM buffer (352 samples * 4 bytes = 1408 bytes per frame)
static uint64_t s_audio_frames_rendered = 0;

// 48-byte AirPlay ALAC magic cookie (24-byte QuickTime/MP4 atom header + 24-byte ALAC info)
// Required by alac_set_info which skips 24 bytes of atom header: (size, frma, alac, size, alac, 0)
static const unsigned char default_alac_magic_cookie[48] = {
    // 24 bytes QuickTime/MP4 atom header
    0x00, 0x00, 0x00, 0x24, // size = 36
    'a',  'l',  'a',  'c',  // frma
    0x00, 0x00, 0x00, 0x14, // alac
    0x00, 0x00, 0x00, 0x00, // size
    0x00, 0x00, 0x00, 0x00, // alac
    0x00, 0x00, 0x00, 0x00, // 0 ?
    // 24 bytes ALAC codec parameters (44100Hz, 16-bit, 2ch, 352 spf)
    0x00, 0x00, 0x01, 0x60, // 352 samples per frame (0x160)
    0x00,                   // 7a (compatible version = 0)
    0x10,                   // sample size (16 bit)
    0x28,                   // historymult (40)
    0x0a,                   // initialhistory (10)
    0x0e,                   // kmodifier (14)
    0x02,                   // channels (2 stereo)
    0x00, 0xff,             // 80 (maxRun = 255)
    0x00, 0x00, 0x00, 0x00, // 82 (max frame size)
    0x00, 0x00, 0x00, 0x00, // 86 (bit rate)
    0x00, 0x00, 0xac, 0x44  // 8a_rate (44100 Hz, 0xac44)
};

static void init_alac_decoder_if_needed() {
    if (!s_alac) {
        s_alac = alac_create(16, 2);
        if (s_alac) {
            alac_set_info(s_alac, (char*)default_alac_magic_cookie);
            LOGI("Native ALAC decoder initialized for 44.1kHz 16-bit Stereo");
        } else {
            LOGE("Failed to allocate native ALAC decoder");
        }
    }
}

bool gstreamer_init() {
    return true; // Android uses low-latency native ALAC decoding and AudioTrack
}

void audio_renderer_init(void *logger, const char* audiosink, const bool *audio_sync, const bool *video_sync, const char *artp_pipeline) {
    (void)logger;
    (void)audiosink;
    (void)audio_sync;
    (void)video_sync;
    (void)artp_pipeline;
    LOGI("Android Audio Renderer initialized with native ALAC decoding");
}

void audio_renderer_start(unsigned char* compression_type) {
    s_is_running = true;
    s_audio_frames_rendered = 0;
    if (compression_type && *compression_type > 0) {
        s_current_compression_type = *compression_type;
    } else {
        s_current_compression_type = 2; // Default ALAC
    }

    if (s_current_compression_type == 2) {
        init_alac_decoder_if_needed();
    }

    jni_on_audio_state_changed(true);
    LOGI("Audio rendering started, compression_type: %d (1=PCM, 2=ALAC, 4/8=AAC)", s_current_compression_type);
}

void audio_renderer_stop() {
    s_is_running = false;
    if (s_alac) {
        alac_free(s_alac);
        s_alac = NULL;
    }
    jni_on_audio_state_changed(false);
    LOGI("Audio rendering stopped (total frames rendered: %llu)", s_audio_frames_rendered);
}

void audio_renderer_render_buffer(unsigned char* data, int *data_len, unsigned short *seqnum, uint64_t *ntp_time) {
    if (!data || !data_len || *data_len <= 0) {
        return;
    }

    if (!s_is_running) {
        s_is_running = true;
        jni_on_audio_state_changed(true);
        LOGI("Audio rendering automatically engaged on incoming frame (compression_type=%d)", s_current_compression_type);
    }

    uint64_t timestamp = ntp_time ? *ntp_time : 0;
    unsigned short seq = seqnum ? *seqnum : 0;

    if (s_current_compression_type == 2) {
        // ALAC compressed audio -> decode natively to 16-bit linear PCM
        init_alac_decoder_if_needed();
        if (s_alac) {
            // CRITICAL: out_bytes MUST be initialized to the capacity of the destination buffer!
            int out_bytes = (int)sizeof(s_pcm_out);
            alac_decode_frame(s_alac, data, s_pcm_out, &out_bytes);
            if (out_bytes > 0) {
                s_audio_frames_rendered++;
                if (s_audio_frames_rendered == 1 || s_audio_frames_rendered % 200 == 0) {
                    LOGD("ALAC decoded frame #%llu: %d bytes PCM", s_audio_frames_rendered, out_bytes);
                }
                // Format 1 = Linear PCM (S16LE stereo 44.1kHz)
                jni_on_audio_frame(s_pcm_out, out_bytes, 1, timestamp, seq);
            }
            return;
        }
    }

    // Direct dispatch for linear PCM (1) or AAC (4 / 8)
    s_audio_frames_rendered++;
    jni_on_audio_frame(data, *data_len, s_current_compression_type, timestamp, seq);
}

void audio_renderer_set_volume(double volume) {
    // AirPlay volume arrives in decibels from -30.0 dB (min) to 0.0 dB (max), or -144.0 dB (mute)
    float linear_vol;
    if (volume <= -30.0) {
        linear_vol = 0.0f;
    } else if (volume >= 0.0) {
        linear_vol = 1.0f;
    } else {
        // Linear fraction of slider: [-30 dB, 0 dB] -> [0.0, 1.0]
        linear_vol = (float)((30.0 + volume) / 30.0);
    }
    LOGD("AirPlay audio volume dB: %f -> linear: %f", volume, linear_vol);
    jni_on_volume_changed(linear_vol);
}

void audio_renderer_flush() {
    LOGD("Audio renderer flush");
}

void audio_renderer_destroy() {
    s_is_running = false;
    if (s_alac) {
        alac_free(s_alac);
        s_alac = NULL;
    }
    LOGI("Audio renderer destroyed");
}

unsigned int audio_renderer_listen(void *loop, int id) {
    (void)loop;
    (void)id;
    return 0;
}
