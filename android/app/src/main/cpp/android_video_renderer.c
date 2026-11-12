#include "android_video_renderer.h"
#include <android/log.h>
#include <string.h>

#define TAG "AndroidVideoRenderer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// External JNI hooks
extern void jni_on_video_frame(const uint8_t *data, int length, uint64_t ntp_time, int nal_count);
extern void jni_on_cover_art(const uint8_t *data, int length);
extern void jni_on_metadata(const char *title, const char *artist, const char *album);
extern void jni_on_client_info(const char *model, const char *name);
extern void jni_on_video_state_changed(bool is_active);

static bool s_is_running = false;
static bool s_is_paused = false;

void video_renderer_init(void *logger, const char *server_name, videoflip_t videoflip[2],
                         const char *parser, const char *rtp_pipeline, const char *decoder,
                         const char *converter, const char *videosink, const char *videosink_options,
                         bool initial_fullscreen, bool video_sync, bool h265_support,
                         bool coverart_support, unsigned int playbin_version, const char *uri) {
    (void)logger;
    (void)server_name;
    (void)videoflip;
    (void)parser;
    (void)rtp_pipeline;
    (void)decoder;
    (void)converter;
    (void)videosink;
    (void)videosink_options;
    (void)initial_fullscreen;
    (void)video_sync;
    (void)h265_support;
    (void)coverart_support;
    (void)playbin_version;
    (void)uri;
    LOGI("Android Video Renderer initialized for Google TV");
}

void video_renderer_start() {
    s_is_running = true;
    s_is_paused = false;
    jni_on_video_state_changed(true);
    LOGI("Video rendering started");
}

void video_renderer_stop() {
    s_is_running = false;
    jni_on_video_state_changed(false);
    LOGI("Video rendering stopped");
}

void video_renderer_set_device_model(const char *model, const char *name) {
    LOGI("Client device model: %s, name: %s", model ? model : "unknown", name ? name : "unknown");
    jni_on_client_info(model, name);
}

void video_renderer_set_track_metadata(const char *title, const char *artist, const char *album) {
    LOGI("Track metadata: title='%s', artist='%s', album='%s'",
         title ? title : "", artist ? artist : "", album ? album : "");
    jni_on_metadata(title, artist, album);
}

void video_renderer_pause() {
    s_is_paused = true;
    LOGI("Video paused");
}

void video_renderer_hls_ready() {
    LOGD("HLS ready");
}

void video_renderer_seek(float position) {
    (void)position;
    LOGD("Video seek: %f", position);
}

void video_renderer_set_start(float position) {
    (void)position;
}

void video_renderer_resume() {
    s_is_paused = false;
    LOGI("Video resumed");
}

int video_renderer_cycle() {
    return 0;
}

bool video_renderer_is_paused() {
    return s_is_paused;
}

bool video_renderer_eos_watch() {
    return false;
}

uint64_t video_renderer_render_buffer(unsigned char* data, int *data_len, int *nal_count, uint64_t *ntp_time) {
    if (!s_is_running || !data || !data_len || *data_len <= 0) {
        return 0;
    }

    uint64_t timestamp = ntp_time ? *ntp_time : 0;
    int count = nal_count ? *nal_count : 0;

    // Direct hardware dispatch to Android MediaCodec via JNI
    jni_on_video_frame(data, *data_len, timestamp, count);

    return 0;
}

void video_renderer_display_jpeg(const void *data, int *data_len) {
    if (data && data_len && *data_len > 0) {
        jni_on_cover_art((const uint8_t*)data, *data_len);
    }
}

void video_renderer_flush() {
    LOGD("Video renderer flush");
}

unsigned int video_renderer_listen(void *loop, int id) {
    (void)loop;
    (void)id;
    return 0;
}

void video_renderer_destroy() {
    s_is_running = false;
    LOGI("Video renderer destroyed");
}

bool waiting_for_x11_window() {
    return false;
}

bool video_get_playback_info(double *duration, double *position, double *seek_start,
                             double *seek_duration, float *rate, bool *buffer_empty, bool *buffer_full) {
    if (duration) *duration = 0.0;
    if (position) *position = 0.0;
    if (seek_start) *seek_start = 0.0;
    if (seek_duration) *seek_duration = 0.0;
    if (rate) *rate = 1.0f;
    if (buffer_empty) *buffer_empty = false;
    if (buffer_full) *buffer_full = true;
    return true;
}

int video_renderer_choose_codec_and_size(bool video_is_jpeg, bool video_is_h265, video_geometry_t *video_geometry) {
    (void)video_is_jpeg;
    (void)video_is_h265;
    (void)video_geometry;
    return 0;
}

void video_renderer_hls_set_volume(double volume) {
    (void)volume;
}

void video_renderer_set_window_handle(uintptr_t handle) {
    (void)handle;
}

bool gstreamer_decoder_check(const char *decoder) {
    (void)decoder;
    return true;
}
