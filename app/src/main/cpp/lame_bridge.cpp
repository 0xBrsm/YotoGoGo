#include <jni.h>
#include <android/log.h>
extern "C" {
#include "lame.h"
}

#define TAG "LameBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_yotogogo_Mp3Encoder_nativeCreate(JNIEnv*, jobject,
                                           jint sampleRate, jint channels, jint bitrate)
{
    lame_global_flags* gfp = lame_init();
    if (!gfp) { LOGE("lame_init failed"); return 0; }

    lame_set_in_samplerate(gfp, sampleRate);
    lame_set_num_channels(gfp, channels);
    lame_set_brate(gfp, bitrate);
    lame_set_quality(gfp, 7);
    lame_set_mode(gfp, channels == 1 ? MONO : JOINT_STEREO);

    if (lame_init_params(gfp) < 0) {
        LOGE("lame_init_params failed");
        lame_close(gfp);
        return 0;
    }
    return reinterpret_cast<jlong>(gfp);
}

JNIEXPORT jbyteArray JNICALL
Java_com_yotogogo_Mp3Encoder_nativeEncode(JNIEnv* env, jobject,
                                           jlong handle, jshortArray pcm,
                                           jint numSamplesPerChannel)
{
    auto* gfp = reinterpret_cast<lame_global_flags*>(handle);
    if (!gfp) return nullptr;

    int mp3BufSize = numSamplesPerChannel * 5 / 4 + 7200;
    auto* mp3Buf = new unsigned char[mp3BufSize];

    jshort* src = env->GetShortArrayElements(pcm, nullptr);
    int channels = lame_get_num_channels(gfp);
    int written = 0;
    if (channels == 1) {
        written = lame_encode_buffer(gfp, src, nullptr, numSamplesPerChannel, mp3Buf, mp3BufSize);
    } else {
        written = lame_encode_buffer_interleaved(gfp, src, numSamplesPerChannel, mp3Buf, mp3BufSize);
    }
    env->ReleaseShortArrayElements(pcm, src, JNI_ABORT);

    jbyteArray result = nullptr;
    if (written > 0) {
        result = env->NewByteArray(written);
        env->SetByteArrayRegion(result, 0, written, reinterpret_cast<const jbyte*>(mp3Buf));
    }
    delete[] mp3Buf;
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_yotogogo_Mp3Encoder_nativeFlush(JNIEnv* env, jobject, jlong handle)
{
    auto* gfp = reinterpret_cast<lame_global_flags*>(handle);
    if (!gfp) return nullptr;

    auto* mp3Buf = new unsigned char[7200];
    int written = lame_encode_flush(gfp, mp3Buf, 7200);

    jbyteArray result = nullptr;
    if (written > 0) {
        result = env->NewByteArray(written);
        env->SetByteArrayRegion(result, 0, written, reinterpret_cast<const jbyte*>(mp3Buf));
    }
    delete[] mp3Buf;
    return result;
}

JNIEXPORT void JNICALL
Java_com_yotogogo_Mp3Encoder_nativeClose(JNIEnv*, jobject, jlong handle)
{
    auto* gfp = reinterpret_cast<lame_global_flags*>(handle);
    if (gfp) lame_close(gfp);
}

} // extern "C"
