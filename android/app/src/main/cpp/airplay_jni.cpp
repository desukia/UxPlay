#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
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
static jmethodID g_mid_onAudioFrame = nullptr;
static jmethodID g_mid_onMetadata = nullptr;
static jmethodID g_mid_onCoverArt = nullptr;
static jmethodID g_mid_onVolumeChanged = nullptr;

static std::atomic<bool> g_is_running(false);
static std::thread g_server_thread;
static raop_t *g_raop = nullptr;
static dnssd_t *g_dnssd = nullptr;
static logger_t *g_logger = nullptr;

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

extern "C" void jni_on_video_state_changed(bool is_active) {
    std::lock_guard<std::mutex> lock(g_cb_mutex);
    if (!g_callback_obj || !g_mid_onStateChanged) return;
    JNIEnv *env = getJniEnv();
    if (!env) return;

    // 0 = IDLE, 1 = PIN_REQUIRED, 2 = MIRRORING, 3 = AUDIO_STREAMING
    int state = is_active ? 2 : 0;
    env->CallVoidMethod(g_callback_obj, g_mid_onStateChanged, (jint)state);
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

extern "C" void jni_on_audio_state_changed(bool is_active) {
    std::lock_guard<std::mutex> lock(g_cb_mutex);
    if (!g_callback_obj || !g_mid_onStateChanged) return;
    JNIEnv *env = getJniEnv();
    if (!env) return;

    int state = is_active ? 3 : 0;
    env->CallVoidMethod(g_callback_obj, g_mid_onStateChanged, (jint)state);
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

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    LOGI("JNI_OnLoad called for UxPlay Google TV");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_appletv_airplay_engine_NativeAirPlay_nativeInit(JNIEnv *env, jobject thiz, jobject callbacks) {
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
        netutils_init();

        g_logger = logger_init();
        logger_set_level(g_logger, LOGGER_INFO);

        // Hardware Mac address emulation (00:11:22:33:44:55 or derived from TV)
        unsigned char hw_addr[6] = {0x00, 0x11, 0x22, 0x33, 0x44, 0x55};

        raop_callbacks_t raop_cbs;
        memset(&raop_cbs, 0, sizeof(raop_cbs));
        raop_cbs.audio_process = audio_renderer_render_buffer;
        raop_cbs.video_process = video_renderer_render_buffer;
        raop_cbs.audio_flush = audio_renderer_flush;
        raop_cbs.video_flush = video_renderer_flush;
        raop_cbs.audio_destroy = audio_renderer_destroy;
        raop_cbs.video_destroy = video_renderer_destroy;
        raop_cbs.audio_set_volume = audio_renderer_set_volume;
        raop_cbs.video_set_volume = video_renderer_hls_set_volume;

        g_raop = raop_init(10, &raop_cbs);
        if (!g_raop) {
            LOGE("Failed to initialize RAOP core");
            g_is_running.store(false);
            return;
        }

        raop_set_log_level(g_raop, LOGGER_INFO);
        raop_set_plist(g_raop, "width", width > 0 ? width : 1920);
        raop_set_plist(g_raop, "height", height > 0 ? height : 1080);
        raop_set_plist(g_raop, "maxFPS", fps > 0 ? fps : 60);
        raop_set_plist(g_raop, "refreshRate", 60);

        if (requirePin) {
            if (!sPin.empty()) {
                raop_set_plist(g_raop, "pin", std::stoi(sPin));
            } else {
                raop_set_plist(g_raop, "pin", 1); // Random PIN
            }
        }

        unsigned short raop_port = 0;
        raop_start_httpd(g_raop, &raop_port);
        raop_set_port(g_raop, raop_port);

        // Start mDNS advertisement as an authentic Apple TV
        g_dnssd = dnssd_init(g_logger);
        if (g_dnssd) {
            dnssd_register_raop(g_dnssd, sName.c_str(), raop_port, (const char*)hw_addr, sizeof(hw_addr));
            dnssd_register_airplay(g_dnssd, sName.c_str(), raop_port, (const char*)hw_addr, sizeof(hw_addr));
            raop_set_dnssd(g_raop, g_dnssd);
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
            raop_stop_httpd(g_raop);
            raop_destroy(g_raop);
            g_raop = nullptr;
        }

        if (g_logger) {
            logger_destroy(g_logger);
            g_logger = nullptr;
        }

        netutils_cleanup();
        LOGI("AirPlay server stopped cleanly");
    });

    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_appletv_airplay_engine_NativeAirPlay_nativeStopServer(JNIEnv *env, jobject thiz) {
    if (!g_is_running.load()) return;

    g_is_running.store(false);
    if (g_server_thread.joinable()) {
        g_server_thread.join();
    }
    LOGI("nativeStopServer completed");
}

extern "C" JNIEXPORT void JNICALL
Java_com_appletv_airplay_engine_NativeAirPlay_nativeSetVolume(JNIEnv *env, jobject thiz, jfloat volume) {
    audio_renderer_set_volume((double)volume);
}
