#include <jni.h>
#include <android/log.h>
#include <cstdlib>
extern "C" {
#include "layer3.h"
}

#define TAG "ShineBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct ShineWrapper {
    shine_t enc;
    int     channels;
    int     samplesPerPass;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_yotogogo_Mp3Encoder_nativeCreate(JNIEnv*, jobject,
                                           jint sampleRate, jint channels, jint bitrate)
{
    shine_config_t cfg;
    shine_set_config_mpeg_defaults(&cfg.mpeg);
    cfg.wave.samplerate = sampleRate;
    cfg.wave.channels   = (channels == 1) ? PCM_MONO : PCM_STEREO;
    cfg.mpeg.bitr       = bitrate;
    cfg.mpeg.mode       = (channels == 1) ? MONO : JOINT_STEREO;

    shine_t enc = shine_initialise(&cfg);
    if (!enc) {
        LOGE("shine_initialise failed");
        return 0;
    }

    auto* w = new ShineWrapper{enc, channels, shine_samples_per_pass(enc)};
    return reinterpret_cast<jlong>(w);
}

JNIEXPORT jbyteArray JNICALL
Java_com_yotogogo_Mp3Encoder_nativeEncode(JNIEnv* env, jobject,
                                           jlong handle, jshortArray pcm)
{
    auto* w = reinterpret_cast<ShineWrapper*>(handle);
    if (!w) return nullptr;

    jsize len = env->GetArrayLength(pcm);
    jshort* src = env->GetShortArrayElements(pcm, nullptr);

    int outBytes = 0;
    unsigned char* mp3 = shine_encode_buffer_interleaved(
        w->enc, reinterpret_cast<int16_t*>(src), &outBytes);

    env->ReleaseShortArrayElements(pcm, src, JNI_ABORT);

    if (!mp3 || outBytes <= 0) return nullptr;

    jbyteArray result = env->NewByteArray(outBytes);
    env->SetByteArrayRegion(result, 0, outBytes, reinterpret_cast<const jbyte*>(mp3));
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_yotogogo_Mp3Encoder_nativeFlush(JNIEnv* env, jobject, jlong handle)
{
    auto* w = reinterpret_cast<ShineWrapper*>(handle);
    if (!w) return nullptr;

    int outBytes = 0;
    unsigned char* mp3 = shine_flush(w->enc, &outBytes);

    if (!mp3 || outBytes <= 0) return nullptr;

    jbyteArray result = env->NewByteArray(outBytes);
    env->SetByteArrayRegion(result, 0, outBytes, reinterpret_cast<const jbyte*>(mp3));
    return result;
}

JNIEXPORT void JNICALL
Java_com_yotogogo_Mp3Encoder_nativeClose(JNIEnv*, jobject, jlong handle)
{
    auto* w = reinterpret_cast<ShineWrapper*>(handle);
    if (w) {
        shine_close(w->enc);
        delete w;
    }
}

JNIEXPORT jint JNICALL
Java_com_yotogogo_Mp3Encoder_nativeSamplesPerPass(JNIEnv*, jobject, jlong handle)
{
    auto* w = reinterpret_cast<ShineWrapper*>(handle);
    return w ? w->samplesPerPass : 0;
}

} // extern "C"
