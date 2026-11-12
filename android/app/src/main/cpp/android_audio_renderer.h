#ifndef ANDROID_AUDIO_RENDERER_H
#define ANDROID_AUDIO_RENDERER_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>

bool gstreamer_init();
void audio_renderer_init(void *logger, const char* audiosink, const bool *audio_sync, const bool *video_sync, const char *artp_pipeline);
void audio_renderer_start(unsigned char* compression_type);
void audio_renderer_stop();
void audio_renderer_render_buffer(unsigned char* data, int *data_len, unsigned short *seqnum, uint64_t *ntp_time);
void audio_renderer_set_volume(double volume);
void audio_renderer_flush();
void audio_renderer_destroy();
unsigned int audio_renderer_listen(void *loop, int id);

#ifdef __cplusplus
}
#endif

#endif // ANDROID_AUDIO_RENDERER_H
