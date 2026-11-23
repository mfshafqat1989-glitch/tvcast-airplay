/*
 * JNI bridge between the UxPlay AirPlay engine (GPLv3) and the Android app.
 * Video (H.264/H.265 Annex-B) and AAC audio are handed to Java (MediaCodec);
 * ALAC music is decoded here and handed over as 16-bit PCM.
 */
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "raop.h"
#include "dnssd.h"
#include "stream.h"
#include "logger.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "AirPlayNative"
#define ALOG(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#else
#define ALOG(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

int  alac_wrapper_init(void);
int  alac_wrapper_decode(const unsigned char *in, int in_len, unsigned char *out, int out_cap);
void alac_wrapper_destroy(void);

/* ------------------------------------------------------------------ globals */

static JavaVM *g_vm = NULL;
static jobject g_cb = NULL;
static jmethodID m_onClient, m_onVideoFormat, m_onVideoData, m_onAudioFormat, m_onAudioData,
                 m_onPcm, m_onVolume, m_onVideoStop, m_onConnections;

static raop_t  *g_raop = NULL;
static dnssd_t *g_dnssd = NULL;
static unsigned short g_port = 0;
static int g_mdns_ok = 0;

static volatile int g_open_connections = 0;
static volatile int g_audio_ct = 0;
static volatile bool g_reset_loop = false, g_reset_httpd = false, g_preserve = false;
static volatile bool g_running = false;
static pthread_t g_reset_thread;
static pthread_key_t g_env_key;
static pthread_once_t g_key_once = PTHREAD_ONCE_INIT;

/* ------------------------------------------------------------------ JNIEnv per thread */

static void detach_thread(void *unused) {
    if (g_vm) (*g_vm)->DetachCurrentThread(g_vm);
}

static void make_key(void) { pthread_key_create(&g_env_key, detach_thread); }

static JNIEnv *get_env(void) {
    JNIEnv *env = NULL;
    if (!g_vm) return NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    pthread_once(&g_key_once, make_key);
#ifdef __ANDROID__
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
#else
    if ((*g_vm)->AttachCurrentThread(g_vm, (void **) &env, NULL) != JNI_OK) return NULL;
#endif
    pthread_setspecific(g_env_key, (void *) 1);
    return env;
}

static void check_exc(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static void call_bytes(jmethodID m, const unsigned char *data, int len, jlong extra, int mode) {
    JNIEnv *env = get_env();
    if (!env || !g_cb || len <= 0) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) { check_exc(env); return; }
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) data);
    if (mode == 0) (*env)->CallVoidMethod(env, g_cb, m, arr);
    else if (mode == 1) (*env)->CallVoidMethod(env, g_cb, m, arr, extra);
    else (*env)->CallVoidMethod(env, g_cb, m, arr, (jint) extra);
    check_exc(env);
    (*env)->DeleteLocalRef(env, arr);
}

static void call_void(jmethodID m) {
    JNIEnv *env = get_env();
    if (!env || !g_cb) return;
    (*env)->CallVoidMethod(env, g_cb, m);
    check_exc(env);
}

static void call_int(jmethodID m, int v) {
    JNIEnv *env = get_env();
    if (!env || !g_cb) return;
    (*env)->CallVoidMethod(env, g_cb, m, (jint) v);
    check_exc(env);
}

/* ------------------------------------------------------------------ UxPlay callbacks */

static void cb_log(void *cls, int level, const char *msg) {
    ALOG("%s", msg);
}

static void cb_conn_init(void *cls) {
    g_open_connections++;
    call_int(m_onConnections, g_open_connections);
}

static void cb_conn_destroy(void *cls) {
    if (g_open_connections > 0) g_open_connections--;
    call_int(m_onConnections, g_open_connections);
}

static void cb_conn_feedback(void *cls) {}

static void cb_conn_reset(void *cls, int reason) {
    ALOG("conn_reset reason %d", reason);
    g_reset_httpd = true;
    g_reset_loop = true;
    call_void(m_onVideoStop);
}

static void cb_video_reset(void *cls, reset_type_t type) {
    ALOG("video_reset type %d", (int) type);
    switch (type) {
    case RESET_TYPE_RTP_TO_HLS_TEARDOWN:
    case RESET_TYPE_HLS_SHUTDOWN:
        g_preserve = true;
        break;
    case RESET_TYPE_ON_VIDEO_PLAY:
        return;
    default:
        g_preserve = false;
        break;
    }
    call_void(m_onVideoStop);
    g_reset_loop = true;
}

static int cb_video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short width_source,
                                       unsigned short height_source, unsigned short x_offset,
                                       unsigned short y_offset, unsigned short width, unsigned short height) {
    JNIEnv *env = get_env();
    if (env && g_cb) {
        (*env)->CallVoidMethod(env, g_cb, m_onVideoFormat,
                               (jint) (codec == VIDEO_CODEC_H265 ? 1 : 0),
                               (jint) width_source, (jint) height_source);
        check_exc(env);
    }
    return 0;
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    call_bytes(m_onVideoData, data->data, data->data_len, (jlong) (data->ntp_time_remote / 1000), 1);
}

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen,
                                bool *isMedia, uint64_t *audioFormat) {
    ALOG("audio format ct=%d spf=%d screen=%d", *ct, *spf, *usingScreen);
    g_audio_ct = *ct;
    if (*ct == 2) alac_wrapper_init();
    call_int(m_onAudioFormat, *ct);
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    if (data->data_len <= 0) return;
    if (data->ct == 2) {
        if (data->data[0] != 0x20) return;
        unsigned char pcm[352 * 2 * 2 + 64];
        int n = alac_wrapper_decode(data->data, data->data_len, pcm, sizeof(pcm));
        if (n > 0) call_bytes(m_onPcm, pcm, n, 0, 0);
    } else {
        if (data->ct == 8) {
            unsigned char b = data->data[0];
            if (!(b == 0x8c || b == 0x8d || b == 0x8e || b == 0x80 || b == 0x81 || b == 0x82)) return;
        }
        call_bytes(m_onAudioData, data->data, data->data_len, data->ct, 2);
    }
}

static void cb_audio_set_volume(void *cls, float volume) {
    JNIEnv *env = get_env();
    if (env && g_cb) {
        (*env)->CallVoidMethod(env, g_cb, m_onVolume, (jfloat) volume);
        check_exc(env);
    }
}

static double cb_audio_set_client_volume(void *cls) { return 0.0; }
static void cb_audio_flush(void *cls) {}
static void cb_video_flush(void *cls) {}
static void cb_video_pause(void *cls) {}
static void cb_video_resume(void *cls) {}
static void cb_audio_set_metadata(void *cls, const void *buffer, int buflen) {}
static void cb_audio_set_coverart(void *cls, const void *buffer, int buflen) {}
static void cb_audio_stop_coverart(void *cls) {}
static void cb_audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {}
static void cb_export_dacp(void *cls, const char *active_remote, const char *dacp_id) {}
static void cb_display_pin(void *cls, char *pin) {}
static void cb_register_client(void *cls, const char *device_id, const char *pk, const char *name) {}
static bool cb_check_register(void *cls, const char *pk) { return true; }
static void cb_get_custom_profile(void *cls, const char **p) { *p = NULL; }

static const char *cb_passwd(void *cls, int *len) {
    *len = 0;
    return NULL;
}

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    *admit = true;
    JNIEnv *env = get_env();
    if (env && g_cb) {
        jstring jn = (*env)->NewStringUTF(env, name ? name : "");
        jstring jm = (*env)->NewStringUTF(env, model ? model : "");
        (*env)->CallVoidMethod(env, g_cb, m_onClient, jn, jm);
        check_exc(env);
        (*env)->DeleteLocalRef(env, jn);
        (*env)->DeleteLocalRef(env, jm);
    }
}

/* ------------------------------------------------------------------ reset loop (mirrors uxplay main_loop) */

static void *reset_thread_fn(void *arg) {
    while (g_running) {
        usleep(100 * 1000);
        if (!g_reset_loop || !g_raop) continue;
        g_reset_loop = false;
        ALOG("resetting AirPlay session (httpd=%d preserve=%d)", g_reset_httpd, g_preserve);
        if (g_reset_httpd) raop_stop_httpd(g_raop);
        if (!g_preserve) raop_remove_known_connections(g_raop);
        if (g_reset_httpd) {
            unsigned short port = raop_get_port(g_raop);
            raop_start_httpd(g_raop, &port);
            raop_set_port(g_raop, port);
        }
        g_reset_httpd = false;
        g_preserve = false;
    }
    return NULL;
}

/* ------------------------------------------------------------------ JNI entry points */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static int parse_mac(const char *s, char out[6]) {
    unsigned int b[6];
    if (sscanf(s, "%x:%x:%x:%x:%x:%x", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6) return -1;
    for (int i = 0; i < 6; i++) out[i] = (char) b[i];
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_tvcast_airplay_AirPlayEngine_nativeStart(JNIEnv *env, jobject thiz, jstring jname, jstring jmac,
                                                  jstring jkeyfile, jint width, jint height, jint fps,
                                                  jboolean h265) {
    if (g_raop) return g_port;

    jclass cls = (*env)->GetObjectClass(env, thiz);
    m_onClient      = (*env)->GetMethodID(env, cls, "onClient", "(Ljava/lang/String;Ljava/lang/String;)V");
    m_onVideoFormat = (*env)->GetMethodID(env, cls, "onVideoFormat", "(III)V");
    m_onVideoData   = (*env)->GetMethodID(env, cls, "onVideoData", "([BJ)V");
    m_onAudioFormat = (*env)->GetMethodID(env, cls, "onAudioFormat", "(I)V");
    m_onAudioData   = (*env)->GetMethodID(env, cls, "onAudioData", "([BI)V");
    m_onPcm         = (*env)->GetMethodID(env, cls, "onPcm", "([B)V");
    m_onVolume      = (*env)->GetMethodID(env, cls, "onVolume", "(F)V");
    m_onVideoStop   = (*env)->GetMethodID(env, cls, "onVideoStop", "()V");
    m_onConnections = (*env)->GetMethodID(env, cls, "onConnections", "(I)V");
    if ((*env)->ExceptionCheck(env)) { check_exc(env); return -10; }
    g_cb = (*env)->NewGlobalRef(env, thiz);

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *mac = (*env)->GetStringUTFChars(env, jmac, NULL);
    const char *keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);
    int result = 0;

    char hw[6];
    if (parse_mac(mac, hw) != 0) { result = -1; goto done; }

    ntp_global_init();

    int err = 0;
    g_dnssd = dnssd_init(name, (int) strlen(name), hw, 6, 0, &err);
    if (err || !g_dnssd) { ALOG("dnssd_init error %d", err); result = -2; goto done; }
    dnssd_set_airplay_features(g_dnssd, 42, h265 ? 1 : 0);   /* H.265 screen mirroring */

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.get_custom_profile = cb_get_custom_profile;

    g_raop = raop_init(&cbs);
    if (!g_raop) { result = -3; goto done; }
    raop_set_log_callback(g_raop, cb_log, NULL);
    raop_set_log_level(g_raop, LOGGER_INFO);
    if (raop_init2(g_raop, 1, mac, keyfile)) {
        ALOG("raop_init2 failed");
        free(g_raop);
        g_raop = NULL;
        result = -4;
        goto done;
    }

    raop_set_plist(g_raop, "width", width);
    raop_set_plist(g_raop, "height", height);
    raop_set_plist(g_raop, "refreshRate", fps);
    raop_set_plist(g_raop, "maxFPS", fps);
    raop_set_plist(g_raop, "overscanned", 0);

    unsigned short tcp[3] = {0, 0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);

    g_port = raop_get_port(g_raop);
    raop_start_httpd(g_raop, &g_port);
    raop_set_port(g_raop, g_port);
    raop_set_dnssd(g_raop, g_dnssd);

    int e1 = dnssd_register_raop(g_dnssd, g_port);
    int e2 = dnssd_register_airplay(g_dnssd, g_port);
    if (e1 || e2) ALOG("built-in mDNS failed (%d, %d): Android NSD will be used instead", e1, e2);
    g_mdns_ok = (e1 == 0 && e2 == 0);
    ALOG("AirPlay started on port %d, features 0x%llx", g_port,
         (unsigned long long) dnssd_get_airplay_features(g_dnssd));

    g_running = true;
    pthread_create(&g_reset_thread, NULL, reset_thread_fn, NULL);
    result = g_port;

done:
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jmac, mac);
    (*env)->ReleaseStringUTFChars(env, jkeyfile, keyfile);
    if (result <= 0 && g_dnssd && !g_raop) {
        dnssd_destroy(g_dnssd);
        g_dnssd = NULL;
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_tvcast_airplay_AirPlayEngine_nativeStop(JNIEnv *env, jobject thiz) {
    if (g_running) {
        g_running = false;
        pthread_join(g_reset_thread, NULL);
    }
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
    }
    if (g_raop) {
        raop_destroy(g_raop);
        g_raop = NULL;
    }
    if (g_dnssd) {
        dnssd_destroy(g_dnssd);
        g_dnssd = NULL;
    }
    alac_wrapper_destroy();
    if (g_cb) {
        (*env)->DeleteGlobalRef(env, g_cb);
        g_cb = NULL;
    }
    g_port = 0;
    g_open_connections = 0;
}

JNIEXPORT jboolean JNICALL
Java_com_tvcast_airplay_AirPlayEngine_nativeMdnsOk(JNIEnv *env, jobject thiz) {
    return g_mdns_ok ? JNI_TRUE : JNI_FALSE;
}

/* which = 0: RAOP TXT record, 1: AirPlay TXT record (DNS length-prefixed strings). */
JNIEXPORT jbyteArray JNICALL
Java_com_tvcast_airplay_AirPlayEngine_nativeGetTxt(JNIEnv *env, jobject thiz, jint which) {
    if (!g_dnssd) return NULL;
    int len = 0;
    const char *txt = which == 0 ? dnssd_get_raop_txt(g_dnssd, &len) : dnssd_get_airplay_txt(g_dnssd, &len);
    if (!txt || len <= 0) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) txt);
    return arr;
}
