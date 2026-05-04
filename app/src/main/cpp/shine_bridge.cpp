#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include "shine.h"

#define TAG "ShineBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct ShineWrapper {
    shine_t  enc;
    int      channels;
    int      samplesPerPass;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_yotogogo_Mp3Encoder_nativeCreate(JNIEnv*, jobject,
                                           jint sampleRate, jint channels, jint bitrate)
{
    shine_config_t cfg;
    shine_set_config_mpeg_defaults(&cfg.mpeg);
    cfg.wave.samplerate = sampleRate;
    cfg.wave.channels   = (channels == 1) ? MONO : STEREO;
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

    // shine expects non-interleaved: separate left/right arrays of samplesPerPass
    int spp = w->samplesPerPass;
    int ch  = w->channels;

    // pcm length must equal spp * ch
    if (len != spp * ch) {
        LOGE("nativeEncode: expected %d shorts, got %d", spp * ch, (int)len);
        env->ReleaseShortArrayElements(pcm, src, JNI_ABORT);
        return nullptr;
    }

    // deinterleave into shine's expected buffer layout
    // shine_encode takes int16_t*[2] (one pointer per channel)
    int16_t* buf[2];
    buf[0] = new int16_t[spp];
    buf[1] = (ch == 2) ? new int16_t[spp] : buf[0];  // mono: both pointers to same buffer

    if (ch == 2) {
        for (int i = 0; i < spp; i++) {
            buf[0][i] = src[i * 2];
            buf[1][i] = src[i * 2 + 1];
        }
    } else {
        memcpy(buf[0], src, spp * sizeof(int16_t));
    }

    env->ReleaseShortArrayElements(pcm, src, JNI_ABORT);

    int outBytes = 0;
    unsigned char* mp3 = shine_encode_buffer(w->enc, buf, &outBytes);

    delete[] buf[0];
    if (ch == 2) delete[] buf[1];

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
