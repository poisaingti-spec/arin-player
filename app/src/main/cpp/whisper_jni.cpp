// JNI bridge: runs whisper.cpp on 16 kHz mono PCM and translates speech to English.
// Output (UTF-8 bytes):  line 1 = detected language code ("ja")
//                        then one line per segment: "<startMs>\t<endMs>\t<noSpeechProb>\t<text>"
#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define TAG "ArinWhisper"

static std::string clean(const char *s) {
    std::string out = s ? s : "";
    for (char &c : out) if (c == '\n' || c == '\t' || c == '\r') c = ' ';
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_arin_player_engine_WhisperLib_initContext(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) __android_log_print(ANDROID_LOG_ERROR, TAG, "failed to load model");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_arin_player_engine_WhisperLib_freeContext(JNIEnv *, jobject, jlong ptr) {
    if (ptr) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_arin_player_engine_WhisperLib_translate(JNIEnv *env, jobject, jlong ptr,
                                                 jfloatArray samples, jint threads,
                                                 jboolean translate) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    std::string out;
    if (ctx) {
        whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        wp.print_realtime = false;
        wp.print_progress = false;
        wp.print_timestamps = false;
        wp.print_special = false;
        wp.translate = translate;      // any language -> English
        wp.language = "auto";          // detect the spoken language
        wp.detect_language = false;    // (true would ONLY detect, no transcription)
        wp.n_threads = threads;
        wp.no_context = true;
        wp.single_segment = false;

        jsize n = env->GetArrayLength(samples);
        jfloat *data = env->GetFloatArrayElements(samples, nullptr);
        int rc = whisper_full(ctx, wp, data, n);
        env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

        if (rc == 0) {
            const char *lang = whisper_lang_str(whisper_full_lang_id(ctx));
            out += lang ? lang : "";
            out += "\n";
            int segs = whisper_full_n_segments(ctx);
            for (int i = 0; i < segs; i++) {
                long t0 = (long) whisper_full_get_segment_t0(ctx, i) * 10;   // 10 ms units -> ms
                long t1 = (long) whisper_full_get_segment_t1(ctx, i) * 10;
                float nsp = whisper_full_get_segment_no_speech_prob(ctx, i);
                out += std::to_string(t0) + "\t" + std::to_string(t1) + "\t" +
                       std::to_string(nsp) + "\t" + clean(whisper_full_get_segment_text(ctx, i)) + "\n";
            }
        } else {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", rc);
        }
    }
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}
