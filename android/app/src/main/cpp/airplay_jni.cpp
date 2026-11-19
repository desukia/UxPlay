#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include <chrono>
#include <android/log.h>
#include <cstring>

#define TAG "AirPlayJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {
#include "../../../../../lib/logger.h"
#include "../../../../../lib/netutils.h"
#include "../../../../../lib/raop.h"
#include "../../../../../lib/stream.h"
#include "../../../../../lib/dnssd.h"
#include "android_video_renderer.h"
#include "android_audio_renderer.h"
}

static JavaVM *g_jvm = nullptr;
static jobject g_callback_obj = nullptr;
static std::mutex g_cb_mutex;

static jmethodID g_mid_onStateChanged = nullptr;
static jmethodID g_mid_onPinCodeRequired = nullptr;
static jmethodID g_mid_onPinCodeDismissed = nullptr;
static jmethodID g_mid_onClientConnected = nullptr;
static jmethodID g_mid_onClientDisconnected = nullptr;
static jmethodID g_mid_onVideoFrame = nullptr;
static jmethodID g_mid_onVideoSizeChanged = nullptr;
static jmethodID g_mid_onAudioFrame = nullptr;
static jmethodID g_mid_onMetadata = nullptr;
static jmethodID g_mid_onCoverArt = nullptr;
static jmethodID g_mid_onVolumeChanged = nullptr;

static std::atomic<bool> g_is_running(false);
static std::atomic<bool> g_video_active(false);
static std::atomic<bool> g_audio_active(false);
static std::atomic<int> g_current_width(1920);
static std::atomic<int> g_current_height(1080);
static std::thread g_server_thread;
static raop_t *g_raop = nullptr;
static dnssd_t *g_dnssd = nullptr;

// Helper to get JNIEnv for current thread
static JNIEnv* getJniEnv() {
    if (!g_jvm) return nullptr;
    JNIEnv *env = nullptr;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("Failed to attach current thread to JVM");
            return nullptr;
        }
    }
    return env;
}

// C-bridge implementations for android_video_renderer & android_audio_renderer
extern "C" void jni_on_video_frame(const uint8_t *data, int length, uint64_t ntp_time, int nal_count) {
    std::lock_guard<std::mutex> lock(g_cb_mutex);
    if (!g_callback_obj || !g_mid_onVideoFrame) return;
    JNIEnv *env = getJniEnv();
    if (!env) return;

    jbyteArray jbarr = env->NewByteArray(length);
    env->SetByteArrayRegion(jbarr, 0, length, (const jbyte*)data);
    env->CallVoidMethod(g_callback_obj, g_mid_onVideoFrame, jbarr, (jint)length, (jlong)ntp_time, (jint)nal_count);
    env->DeleteLocalRef(jbarr);
}

extern "C" void jni_on_cover_art(const uint8_t *data, int length) {
    std::lock_guard<std::mutex> lock(g_cb_mutex);
    if (!g_callback_obj || !g_mid_onCoverArt) return;
    JNIEnv *env = getJniEnv();
    if (!env) return;

    jbyteArray jbarr = env->NewByteArray(length);
    env->SetByteArrayRegion(jbarr, 0, length, (const jbyte*)data);
    env->CallVoidMethod(g_callback_obj, g_mid_onCoverArt, jbarr);
    env->DeleteLocalRef(jbarr);
}

extern "C" void jni_on_metadata(const char *title, const char *artist, const char *album) {
    std::lock_guard<std::mutex> lock(g_cb_mutex);
    if (!g_callback_obj || !g_mid_onMetadata) return;
    JNIEnv *env = getJniEnv();
    if (!env) return;

    jstring jtitle = env->NewStringUTF(title ? title : "");
    jstring jartist = env->NewStringUTF(artist ? artist : "");
    jstring jalbum = env->NewStringUTF(album ? album : "");
    env->CallVoidMethod(g_callback_obj, g_mid_onMetadata, jtitle, jartist, jalbum);
    env->DeleteLocalRef(jtitle);
    env->DeleteLocalRef(jartist);
    env->DeleteLocalRef(jalbum);
}

extern "C" void jni_on_client_info(const char *model, const char *name) {
    std::lock_guard<std::mutex> lock(g_cb_mutex);
    if (!g_callback_obj || !g_mid_onClientConnected) return;
    JNIEnv *env = getJniEnv();
    if (!env) return;

    jstring jmodel = env->NewStringUTF(model ? model : "Apple Device");
    jstring jname = env->NewStringUTF(name ? name : "AirPlay Client");
    env->CallVoidMethod(g_callback_obj, g_mid_onClientConnected, jname, jmodel);
    env->DeleteLocalRef(jmodel);
    env->DeleteLocalRef(jname);
}

static void update_airplay_state() {
    std::lock_guard<std::mutex> lock(g_cb_mutex);
    if (!g_callback_obj || !g_mid_onStateChanged) return;
    JNIEnv *env = getJniEnv();
    if (!env) return;

    // 0 = IDLE, 1 = PIN_REQUIRED, 2 = MIRRORING, 3 = AUDIO_STREAMING
    int state = 0;
    if (g_video_active.load()) {
        state = 2; // Screen mirroring has precedence
    } else if (g_audio_active.load()) {
        state = 3; // Standalone audio streaming
    } else {
        state = 0; // Idle
    }
    LOGI("AirPlay unified state: %d (video=%d, audio=%d)",
         state, g_video_active.load() ? 1 : 0, g_audio_active.load() ? 1 : 0);
    env->CallVoidMethod(g_callback_obj, g_mid_onStateChanged, (jint)state);
}

extern "C" void jni_on_video_size_changed(int width, int height) {
    std::lock_guard<std::mutex> lock(g_cb_mutex);
    if (!g_callback_obj || !g_mid_onVideoSizeChanged) return;
    JNIEnv *env = getJniEnv();
    if (!env) return;

    env->CallVoidMethod(g_callback_obj, g_mid_onVideoSizeChanged, (jint)width, (jint)height);
}

extern "C" void jni_on_video_state_changed(bool is_active) {
    g_video_active.store(is_active);
    update_airplay_state();
}

extern "C" void jni_on_audio_state_changed(bool is_active) {
    g_audio_active.store(is_active);
    update_airplay_state();
}

extern "C" void jni_on_audio_frame(const uint8_t *data, int length, int compression_type, uint64_t ntp_time, unsigned short seqnum) {
    std::lock_guard<std::mutex> lock(g_cb_mutex);
    if (!g_callback_obj || !g_mid_onAudioFrame) return;
    JNIEnv *env = getJniEnv();
    if (!env) return;

    jbyteArray jbarr = env->NewByteArray(length);
    env->SetByteArrayRegion(jbarr, 0, length, (const jbyte*)data);
    env->CallVoidMethod(g_callback_obj, g_mid_onAudioFrame, jbarr, (jint)length, (jint)compression_type, (jlong)ntp_time, (jint)seqnum);
    env->DeleteLocalRef(jbarr);
}

extern "C" void jni_on_volume_changed(float volume) {
    std::lock_guard<std::mutex> lock(g_cb_mutex);
    if (!g_callback_obj || !g_mid_onVolumeChanged) return;
    JNIEnv *env = getJniEnv();
    if (!env) return;

    env->CallVoidMethod(g_callback_obj, g_mid_onVolumeChanged, (jfloat)volume);
}

// PIN code callback from RAOP core
static void on_pin_code_cb(void *cls, const char *pin) {
    (void)cls;
    LOGI("Apple TV AirPlay PIN code requested: %s", pin ? pin : "NULL");
    std::lock_guard<std::mutex> lock(g_cb_mutex);
    if (!g_callback_obj || !g_mid_onPinCodeRequired) return;
    JNIEnv *env = getJniEnv();
    if (!env) return;

    if (pin && strlen(pin) > 0) {
        jstring jpin = env->NewStringUTF(pin);
        env->CallVoidMethod(g_callback_obj, g_mid_onPinCodeRequired, jpin);
        env->DeleteLocalRef(jpin);
    } else {
        if (g_mid_onPinCodeDismissed) {
            env->CallVoidMethod(g_callback_obj, g_mid_onPinCodeDismissed);
        }
    }
}

// RAOP Callback bridge functions matching raop_callbacks_s in lib/raop.h
static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void)cls;
    (void)ntp;
    if (data && data->data && data->data_len > 0) {
        audio_renderer_render_buffer(data->data, &data->data_len, &data->seqnum, &data->ntp_time_remote);
    }
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void)cls;
    (void)ntp;
    if (data && data->data && data->data_len > 0) {
        video_renderer_render_buffer(data->data, &data->data_len, &data->nal_count, &data->ntp_time_remote);
    }
}

static void cb_video_pause(void *cls) {
    (void)cls;
    video_renderer_pause();
}

static void cb_video_resume(void *cls) {
    (void)cls;
    video_renderer_resume();
}

static void cb_conn_init(void *cls) {
    (void)cls;
    LOGI("cb_conn_init: AirPlay client connected");
}

static void cb_conn_destroy(void *cls) {
    (void)cls;
    LOGI("cb_conn_destroy: AirPlay client disconnected");
    video_renderer_stop();
    audio_renderer_stop();
    g_video_active.store(false);
    g_audio_active.store(false);
    update_airplay_state();
}

static void cb_audio_flush(void *cls) {
    (void)cls;
    audio_renderer_flush();
}

static void cb_video_flush(void *cls) {
    (void)cls;
    video_renderer_flush();
}

static double cb_audio_set_client_volume(void *cls) {
    (void)cls;
    return 1.0;
}

static void cb_audio_set_volume(void *cls, float volume) {
    (void)cls;
    audio_renderer_set_volume((double)volume);
}

// DMAP metadata parser for track title, artist, and album
static void parse_and_dispatch_dmap(const uint8_t *data, int len) {
    if (!data || len < 8) return;
    char title[256] = {0};
    char artist[256] = {0};
    char album[256] = {0};

    int pos = 0;
    if (memcmp(data, "mlit", 4) == 0) {
        pos += 8;
    }

    while (pos + 8 <= len) {
        char tag[5] = {0};
        memcpy(tag, data + pos, 4);
        int tag_len = (data[pos+4] << 24) | (data[pos+5] << 16) | (data[pos+6] << 8) | data[pos+7];
        pos += 8;
        if (tag_len < 0 || pos + tag_len > len) break;

        if (strcmp(tag, "minm") == 0 && tag_len < (int)sizeof(title)) {
            memcpy(title, data + pos, tag_len);
            title[tag_len] = '\0';
        } else if (strcmp(tag, "asar") == 0 && tag_len < (int)sizeof(artist)) {
            memcpy(artist, data + pos, tag_len);
            artist[tag_len] = '\0';
        } else if (strcmp(tag, "asal") == 0 && tag_len < (int)sizeof(album)) {
            memcpy(album, data + pos, tag_len);
            album[tag_len] = '\0';
        }
        pos += tag_len;
    }

    if (title[0] != '\0' || artist[0] != '\0') {
        LOGI("DMAP track metadata parsed: title='%s', artist='%s', album='%s'", title, artist, album);
        jni_on_metadata(title, artist, album);
    }
}

static void cb_audio_set_metadata(void *cls, const void *buffer, int buflen) {
    (void)cls;
    if (buffer && buflen > 0) {
        parse_and_dispatch_dmap((const uint8_t*)buffer, buflen);
    }
}

static void cb_audio_set_coverart(void *cls, const void *buffer, int buflen) {
    (void)cls;
    if (buffer && buflen > 0) {
        jni_on_cover_art((const uint8_t*)buffer, buflen);
    }
}

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    (void)cls;
    LOGI("cb_audio_get_format: ct=%d, spf=%d, usingScreen=%d, isMedia=%d",
         ct ? *ct : 0, spf ? *spf : 0,
         usingScreen ? *usingScreen : 0, isMedia ? *isMedia : 0);

    // Retain client's requested compression type (2 = ALAC, 8 = AAC-ELD, 4 = AAC-LC, 1 = PCM)
    if (ct && *ct == 0) {
        *ct = 2; // Default to ALAC
    }
    if (spf && *spf == 0) {
        *spf = 352;
    }
    if (usingScreen && *usingScreen) {
        // Screen mirroring active - audio plays alongside video
        g_video_active.store(true);
    } else {
        // Standalone audio streaming (Apple Music, Spotify, Podcasts)
        g_video_active.store(false);
    }
    if (audioFormat) *audioFormat = 0;

    // Start audio renderer with negotiated compression type
    audio_renderer_start(ct);
    update_airplay_state();
}

static void cb_display_pin(void *cls, char *pin) {
    (void)cls;
    on_pin_code_cb(nullptr, pin);
}

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    (void)cls;
    (void)deviceid;
    if (admit) *admit = true;
    jni_on_client_info(model, name);
}

static int cb_video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short width_source,
                                       unsigned short height_source, unsigned short x_offset,
                                       unsigned short y_offset, unsigned short width, unsigned short height) {
    (void)cls;
    (void)codec;
    (void)x_offset;
    (void)y_offset;
    (void)width;
    (void)height;
    int w = width_source > 0 ? (int)width_source : 1920;
    int h = height_source > 0 ? (int)height_source : 1080;
    LOGI("cb_video_set_codec_and_size: stream geometry is %dx%d (crop %dx%d)", w, h, width, height);
    g_current_width.store(w);
    g_current_height.store(h);
    jni_on_video_size_changed(w, h);
    video_renderer_start();
    g_video_active.store(true);
    update_airplay_state();
    return 0;
}

static void cb_conn_feedback(void *cls) { (void)cls; }
static void cb_conn_reset(void *cls, int reason) { (void)cls; (void)reason; }
static void cb_video_reset(void *cls, reset_type_t reset_type) { (void)cls; (void)reset_type; }

// Logger bridge to Android Logcat
static void uxplay_log_callback(void *cls, int level, const char *msg) {
    (void)cls;
    int android_level = ANDROID_LOG_INFO;
    if (level <= LOGGER_ERR) {
        android_level = ANDROID_LOG_ERROR;
    } else if (level <= LOGGER_WARNING) {
        android_level = ANDROID_LOG_WARN;
    } else if (level <= LOGGER_INFO) {
        android_level = ANDROID_LOG_INFO;
    } else {
        android_level = ANDROID_LOG_DEBUG;
    }
    __android_log_print(android_level, "UxPlayCore", "%s", msg ? msg : "");
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    LOGI("JNI_OnLoad called for UxPlay Google TV");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_appletv_airplay_engine_NativeAirPlay_nativeInit(JNIEnv *env, jobject thiz, jobject callbacks) {
    (void)thiz;
    std::lock_guard<std::mutex> lock(g_cb_mutex);
    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }

    g_callback_obj = env->NewGlobalRef(callbacks);
    jclass cbClass = env->GetObjectClass(callbacks);

    g_mid_onStateChanged = env->GetMethodID(cbClass, "onStateChanged", "(I)V");
    g_mid_onPinCodeRequired = env->GetMethodID(cbClass, "onPinCodeRequired", "(Ljava/lang/String;)V");
    g_mid_onPinCodeDismissed = env->GetMethodID(cbClass, "onPinCodeDismissed", "()V");
    g_mid_onClientConnected = env->GetMethodID(cbClass, "onClientConnected", "(Ljava/lang/String;Ljava/lang/String;)V");
    g_mid_onClientDisconnected = env->GetMethodID(cbClass, "onClientDisconnected", "()V");
    g_mid_onVideoFrame = env->GetMethodID(cbClass, "onVideoFrame", "([BIJI)V");
    g_mid_onVideoSizeChanged = env->GetMethodID(cbClass, "onVideoSizeChanged", "(II)V");
    g_mid_onAudioFrame = env->GetMethodID(cbClass, "onAudioFrame", "([BIIJI)V");
    g_mid_onMetadata = env->GetMethodID(cbClass, "onMetadata", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    g_mid_onCoverArt = env->GetMethodID(cbClass, "onCoverArt", "([B)V");
    g_mid_onVolumeChanged = env->GetMethodID(cbClass, "onVolumeChanged", "(F)V");

    LOGI("NativeAirPlay initialized with callbacks");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_appletv_airplay_engine_NativeAirPlay_nativeStartServer(
        JNIEnv *env, jobject thiz,
        jstring jserverName, jint width, jint height, jint fps,
        jboolean requirePin, jstring jfixedPin) {
    (void)thiz;

    if (g_is_running.load()) {
        LOGI("Server is already running");
        return 0;
    }

    const char *serverName = env->GetStringUTFChars(jserverName, nullptr);
    const char *fixedPin = jfixedPin ? env->GetStringUTFChars(jfixedPin, nullptr) : nullptr;

    std::string sName = serverName ? serverName : "Apple TV";
    std::string sPin = fixedPin ? fixedPin : "";

    if (serverName) env->ReleaseStringUTFChars(jserverName, serverName);
    if (fixedPin) env->ReleaseStringUTFChars(jfixedPin, fixedPin);

    g_is_running.store(true);

    g_server_thread = std::thread([sName, width, height, fps, requirePin, sPin]() {
        // Hardware MAC address emulation
        char hw_addr[6] = {0x00, 0x11, 0x22, 0x33, 0x44, 0x55};

        raop_callbacks_t raop_cbs;
        memset(&raop_cbs, 0, sizeof(raop_cbs));
        raop_cbs.cls = nullptr;
        raop_cbs.audio_process = cb_audio_process;
        raop_cbs.video_process = cb_video_process;
        raop_cbs.video_pause = cb_video_pause;
        raop_cbs.video_resume = cb_video_resume;
        raop_cbs.conn_feedback = cb_conn_feedback;
        raop_cbs.conn_reset = cb_conn_reset;
        raop_cbs.video_reset = cb_video_reset;
        raop_cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
        raop_cbs.conn_init = cb_conn_init;
        raop_cbs.conn_destroy = cb_conn_destroy;
        raop_cbs.audio_flush = cb_audio_flush;
        raop_cbs.video_flush = cb_video_flush;
        raop_cbs.audio_set_client_volume = cb_audio_set_client_volume;
        raop_cbs.audio_set_volume = cb_audio_set_volume;
        raop_cbs.audio_set_metadata = cb_audio_set_metadata;
        raop_cbs.audio_set_coverart = cb_audio_set_coverart;
        raop_cbs.audio_get_format = cb_audio_get_format;
        raop_cbs.display_pin = cb_display_pin;
        raop_cbs.report_client_request = cb_report_client_request;

        g_raop = raop_init(&raop_cbs);
        if (!g_raop) {
            LOGE("Failed to initialize RAOP core");
            g_is_running.store(false);
            return;
        }

        raop_set_log_callback(g_raop, uxplay_log_callback, nullptr);
        raop_set_log_level(g_raop, LOGGER_INFO);

        const char *mac_str = "00:11:22:33:44:55";
        const char *keyfile = "";
        if (raop_init2(g_raop, 1, mac_str, keyfile) != 0) {
            LOGE("Failed in raop_init2");
            raop_destroy(g_raop);
            g_raop = nullptr;
            g_is_running.store(false);
            return;
        }

        raop_set_plist(g_raop, "width", width > 0 ? width : 1920);
        raop_set_plist(g_raop, "height", height > 0 ? height : 1080);
        raop_set_plist(g_raop, "maxFPS", fps > 0 ? fps : 60);
        raop_set_plist(g_raop, "refreshRate", 60);

        unsigned char pin_pw = 0;
        if (requirePin) {
            pin_pw = 1;
            if (!sPin.empty()) {
                raop_set_plist(g_raop, "pin", std::stoi(sPin));
            } else {
                raop_set_plist(g_raop, "pin", 1);
            }
        }

        unsigned short tcp[2] = {0, 0};
        unsigned short udp[3] = {0, 0, 0};
        raop_set_tcp_ports(g_raop, tcp);
        raop_set_udp_ports(g_raop, udp);

        unsigned short raop_port = raop_get_port(g_raop);
        raop_start_httpd(g_raop, &raop_port);
        raop_set_port(g_raop, raop_port);

        int dnssd_error = 0;
        g_dnssd = dnssd_init(sName.c_str(), (int)sName.length(), hw_addr, (int)sizeof(hw_addr), pin_pw, &dnssd_error);
        if (g_dnssd && !dnssd_error) {
            dnssd_register_raop(g_dnssd, raop_port);
            dnssd_register_airplay(g_dnssd, raop_port);
            raop_set_dnssd(g_raop, g_dnssd);
        } else {
            LOGE("dnssd_init failed: error %d", dnssd_error);
        }

        LOGI("AirPlay server started as '%s' on port %d with resolution %dx%d@%d",
             sName.c_str(), raop_port, width, height, fps);

        while (g_is_running.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }

        if (g_dnssd) {
            dnssd_unregister_raop(g_dnssd);
            dnssd_unregister_airplay(g_dnssd);
            dnssd_destroy(g_dnssd);
            g_dnssd = nullptr;
        }

        if (g_raop) {
            raop_destroy(g_raop);
            g_raop = nullptr;
        }

        LOGI("AirPlay server stopped cleanly");
    });

    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_appletv_airplay_engine_NativeAirPlay_nativeStopServer(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (!g_is_running.load()) return;

    g_is_running.store(false);
    if (g_server_thread.joinable()) {
        g_server_thread.join();
    }
    LOGI("nativeStopServer completed");
}

extern "C" JNIEXPORT void JNICALL
Java_com_appletv_airplay_engine_NativeAirPlay_nativeSetVolume(JNIEnv *env, jobject thiz, jfloat volume) {
    (void)env;
    (void)thiz;
    audio_renderer_set_volume((double)volume);
}
