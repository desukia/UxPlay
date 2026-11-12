#include "android_audio_renderer.h"
#include <android/log.h>
#include <string.h>

#define TAG "AndroidAudioRenderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// External JNI hooks
extern void jni_on_audio_frame(const uint8_t *data, int length, int compression_type, uint64_t ntp_time, unsigned short seqnum);
extern void jni_on_volume_changed(float volume);
extern void jni_on_audio_state_changed(bool is_active);

static bool s_is_running = false;
static unsigned char s_current_compression_type = 1; // 1 = LPCM, 2 = ALAC, 4 = AAC

bool gstreamer_init() {
    return true; // No GStreamer needed on Android; using Android AudioTrack
}

void audio_renderer_init(void *logger, const char* audiosink, const bool *audio_sync, const bool *video_sync, const char *artp_pipeline) {
    (void)logger;
    (void)audiosink;
    (void)audio_sync;
    (void)video_sync;
    (void)artp_pipeline;
    LOGI("Android Audio Renderer initialized for Google TV");
}

void audio_renderer_start(unsigned char* compression_type) {
    s_is_running = true;
    s_current_compression_type = compression_type ? *compression_type : 1;
    jni_on_audio_state_changed(true);
    LOGI("Audio rendering started, compression_type: %d (1=PCM, 2=ALAC, 4=AAC)", s_current_compression_type);
}

void audio_renderer_stop() {
    s_is_running = false;
    jni_on_audio_state_changed(false);
    LOGI("Audio rendering stopped");
}

void audio_renderer_render_buffer(unsigned char* data, int *data_len, unsigned short *seqnum, uint64_t *ntp_time) {
    if (!s_is_running || !data || !data_len || *data_len <= 0) {
        return;
    }

    uint64_t timestamp = ntp_time ? *ntp_time : 0;
    unsigned short seq = seqnum ? *seqnum : 0;

    // Send audio buffer directly to Android AudioTrack via JNI
    jni_on_audio_frame(data, *data_len, s_current_compression_type, timestamp, seq);
}

void audio_renderer_set_volume(double volume) {
    LOGD("AirPlay audio volume set to: %f", volume);
    jni_on_volume_changed((float)volume);
}

void audio_renderer_flush() {
    LOGD("Audio renderer flush");
}

void audio_renderer_destroy() {
    s_is_running = false;
    LOGI("Audio renderer destroyed");
}

unsigned int audio_renderer_listen(void *loop, int id) {
    (void)loop;
    (void)id;
    return 0;
}
