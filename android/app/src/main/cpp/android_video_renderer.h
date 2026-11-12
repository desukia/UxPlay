#ifndef ANDROID_VIDEO_RENDERER_H
#define ANDROID_VIDEO_RENDERER_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>

typedef enum videoflip_e {
    NONE,
    LEFT,
    RIGHT,
    INVERT,
    VFLIP,
    HFLIP,
} videoflip_t;

typedef struct video_geometry_s {
    int width_source;
    int height_source;
    int x_offset;
    int y_offset;
    int width;
    int height;
} video_geometry_t;

void video_renderer_init(void *logger, const char *server_name, videoflip_t videoflip[2],
                         const char *parser, const char *rtp_pipeline, const char *decoder,
                         const char *converter, const char *videosink, const char *videosink_options,
                         bool initial_fullscreen, bool video_sync, bool h265_support,
                         bool coverart_support, unsigned int playbin_version, const char *uri);
void video_renderer_start();
void video_renderer_stop();
void video_renderer_set_device_model(const char *model, const char *name);
void video_renderer_set_track_metadata(const char *title, const char *artist, const char *album);
void video_renderer_pause();
void video_renderer_hls_ready();
void video_renderer_seek(float position);
void video_renderer_set_start(float position);
void video_renderer_resume();
int video_renderer_cycle();
bool video_renderer_is_paused();
bool video_renderer_eos_watch();
uint64_t video_renderer_render_buffer(unsigned char* data, int *data_len, int *nal_count, uint64_t *ntp_time);
void video_renderer_display_jpeg(const void *data, int *data_len);
void video_renderer_flush();
unsigned int video_renderer_listen(void *loop, int id);
void video_renderer_destroy();
bool waiting_for_x11_window();
bool video_get_playback_info(double *duration, double *position, double *seek_start, double *seek_duration, float *rate, bool *buffer_empty, bool *buffer_full);
int video_renderer_choose_codec_and_size(bool video_is_jpeg, bool video_is_h265, video_geometry_t *video_geometry);
void video_renderer_hls_set_volume(double volume);
void video_renderer_set_window_handle(uintptr_t handle);
bool gstreamer_decoder_check(const char *decoder);

#ifdef __cplusplus
}
#endif

#endif // ANDROID_VIDEO_RENDERER_H
