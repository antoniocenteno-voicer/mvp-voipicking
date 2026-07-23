#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "VoxWhisperJNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// The picker only ever speaks a short number (digito verificador / quantidade)
// or one of a handful of confirmation commands. Without any hint, whisper's
// language-model prior favors common everyday words over an out-of-context
// number, so a short, clipped utterance like "dez" gets misheard as the far
// more common word "desce" ("go down!"). Priming the decoder with this
// domain's vocabulary (numbers + PickingCommand keywords, see
// state/PickingCommand.kt) as an initial_prompt biases it back towards the
// words that are actually possible here.
static const char *DOMAIN_PROMPT_PT =
        "zero um uma dois duas tres quatro cinco seis meia sete oito nove dez onze doze "
        "treze catorze quatorze quinze dezesseis dezasseis dezessete dezoito dezenove vinte "
        "trinta quarenta cinquenta sessenta setenta oitenta noventa cem "
        "confirmado confirmo ok certo cheguei beleza "
        "repete repetir de novo nao entendi "
        "faltou divergencia diferente quebrado avariado "
        "cancelar para parar sair";

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context *context =
            whisper_init_from_file_with_params(model_path_chars, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    whisper_free((struct whisper_context *) context_ptr);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language_str) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);
    const char *language_chars = (*env)->GetStringUTFChars(env, language_str, NULL);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = language_chars;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.initial_prompt = DOMAIN_PROMPT_PT;
    // Each recording is exactly one short command/utterance, never a stream.
    // Leaving this false lets whisper's internal seek loop treat trailing
    // silence as a new segment and hallucinate a repeat of the last words
    // (e.g. "3,5 3,5"). Forcing a single segment removes that repeat.
    params.single_segment = true;
    params.token_timestamps = false;

    // whisper.cpp's encoder always attends over the full 1500-frame (30s) audio
    // context unless told otherwise, even when the clip is 1-2s long -- this is
    // the dominant cost for short utterances. audio_ctx is documented as an
    // EXPERIMENTAL speed-up that "can significantly reduce the quality of the
    // output": the model was only ever trained with n_audio_ctx=1500, so an
    // aggressively small value (we previously tried samples/320, ~100-200 for
    // a short number) pushes it out of that distribution and the decoder loses
    // its stopping cue, hallucinating a repeat of the last word(s) (e.g.
    // "16 16", "um seis. um seis."). 768 is the exact constant whisper.cpp's
    // own short-command examples (stream.wasm, command.wasm) use for this
    // ("partial encoder context for better performance") and is validated not
    // to trigger that failure mode. Mel frames are 10ms/hop and the encoder
    // conv downsamples by 2x, so audio_ctx frames ~= samples / 320; only go
    // above 768 if the clip is actually longer than that implies.
    int audio_ctx = (int) (audio_data_length / 320) + 32;
    if (audio_ctx < 768) audio_ctx = 768;
    if (audio_ctx > 1500) audio_ctx = 1500;
    params.audio_ctx = audio_ctx;

    whisper_reset_timings(context);

    LOGI("running whisper_full, lang=%s, samples=%d", language_chars, (int) audio_data_length);
    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGW("whisper_full failed");
    }

    (*env)->ReleaseStringUTFChars(env, language_str, language_chars);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_full_n_segments((struct whisper_context *) context_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    const char *text = whisper_full_get_segment_text((struct whisper_context *) context_ptr, index);
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTokenCount(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint segment_index) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_full_n_tokens((struct whisper_context *) context_ptr, segment_index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTokenText(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint segment_index, jint token_index) {
    UNUSED(thiz);
    const char *text = whisper_full_get_token_text(
            (struct whisper_context *) context_ptr, segment_index, token_index);
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jfloat JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTokenProb(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint segment_index, jint token_index) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_full_get_token_p(
            (struct whisper_context *) context_ptr, segment_index, token_index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
