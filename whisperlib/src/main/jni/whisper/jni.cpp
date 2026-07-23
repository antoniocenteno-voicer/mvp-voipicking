#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include "whisper.h"
#include "ggml.h"
#include "grammar-parser.h"

#define UNUSED(x) (void)(x)
#define TAG "VoxWhisperJNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    const char *model_path_chars = env->GetStringUTFChars(model_path_str, NULL);
    struct whisper_context *context =
            whisper_init_from_file_with_params(model_path_chars, whisper_context_default_params());
    env->ReleaseStringUTFChars(model_path_str, model_path_chars);
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
        jfloatArray audio_data, jstring language_str, jstring prompt_str, jstring grammar_str) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = env->GetFloatArrayElements(audio_data, NULL);
    const jsize audio_data_length = env->GetArrayLength(audio_data);
    const char *language_chars = env->GetStringUTFChars(language_str, NULL);
    // Caller (PickingStateMachine.promptDeVoz, app side) builds this per current picking
    // state -- only the vocabulary actually valid right now, not a fixed app-wide list. A
    // narrower prompt means less lexical competition for the decoder's language-model prior,
    // which is what previously caused short/out-of-context utterances (e.g. "dez" misheard as
    // "desce") to lose to more common everyday words.
    const char *prompt_chars = env->GetStringUTFChars(prompt_str, NULL);
    // GBNF grammar (PickingStateMachine.gramaticaDeVoz) HARD-restricts the decoder's output to
    // the exact set of utterances valid in the current state -- unlike initial_prompt (a soft
    // bias), a nongrammar token has its logit driven down by grammar_penalty, so out-of-class
    // errors (a number heard as an everyday word) can't be emitted at all. Empty string = no
    // grammar (behave exactly as before). This is the main lever to make the fast base model
    // assertive enough without a slower/bigger model.
    const char *grammar_chars = env->GetStringUTFChars(grammar_str, NULL);

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
    params.initial_prompt = prompt_chars;
    // Each recording is exactly one short command/utterance, never a stream.
    // Leaving this false lets whisper's internal seek loop treat trailing
    // silence as a new segment and hallucinate a repeat of the last words
    // (e.g. "3,5 3,5"). Forcing a single segment removes that repeat.
    params.single_segment = true;
    params.token_timestamps = false;
    // The clip is one short command spoken cleanly: fix temperature at 0 (pure greedy) and
    // disable the temperature-fallback ladder (temperature_inc = 0). The fallback re-runs the
    // whole decode at higher temperatures whenever entropy/logprob thresholds trip -- extra
    // passes we don't want to pay for on a latency-critical short utterance, and which would
    // also relax the grammar's effect. suppress_blank/suppress_nst drop leading-blank and
    // non-speech tokens so the decoder spends its budget on actual words.
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f;
    params.suppress_blank = true;
    params.suppress_nst = true;

    // Grammar must be parsed here and kept alive until whisper_full returns: c_rules() hands
    // back pointers into parsed.rules, so both the parse_state and the c_rules vector have to
    // outlive the call. Start rule is "root" by GBNF convention. Penalty 100.0f matches the
    // whisper.cpp command example -- effectively a hard constraint (nongrammar logits crushed).
    grammar_parser::parse_state parsed;
    std::vector<const whisper_grammar_element *> grammar_rules;
    bool grammar_aplicada = false;
    if (grammar_chars != NULL && grammar_chars[0] != '\0') {
        parsed = grammar_parser::parse(grammar_chars);
        if (!parsed.rules.empty() && parsed.symbol_ids.find("root") != parsed.symbol_ids.end()) {
            grammar_rules = parsed.c_rules();
            params.grammar_rules   = grammar_rules.data();
            params.n_grammar_rules = grammar_rules.size();
            params.i_start_rule    = parsed.symbol_ids.at("root");
            params.grammar_penalty = 100.0f;
            grammar_aplicada = true;
            LOGI("grammar aplicada: %zu regras", grammar_rules.size());
        } else {
            LOGW("grammar ignorada: parse falhou ou sem regra 'root'");
        }
    }

    // whisper.cpp's encoder always attends over the full 1500-frame (30s) audio context unless
    // told otherwise, even when the clip is 1-2s long -- and self-attention is ~O(audio_ctx²),
    // so this is BY FAR the dominant cost for a short command on a CPU without SIMD accel (the
    // small model on the test device). audio_ctx is an EXPERIMENTAL speed-up that "can reduce
    // quality": the model was only trained at n_audio_ctx=1500, so too small a value pushes it
    // out of distribution and the decoder used to lose its stopping cue, repeating the last
    // word ("16 16", "um seis. um seis."), which is why the floor used to be 768.
    //
    // The GBNF grammar removes exactly that failure mode: root ::= ws (cmd|numero) ws matches
    // ONE utterance then only whitespace/END, so a repeat is not even emittable. That lets us
    // drop the floor hard (768 -> 256) ONLY when a grammar is applied -- ~9x less attention
    // work -- which is the whole latency play for the small model. Without a grammar we keep
    // the safe 768 floor. Mel frames are 10ms/hop, encoder conv downsamples 2x, so
    // audio_ctx frames ~= samples / 320.
    int piso = grammar_aplicada ? 256 : 768;
    int audio_ctx = (int) (audio_data_length / 320) + 16;
    if (audio_ctx < piso) audio_ctx = piso;
    if (audio_ctx > 1500) audio_ctx = 1500;
    params.audio_ctx = audio_ctx;

    whisper_reset_timings(context);

    LOGI("running whisper_full, lang=%s, samples=%d", language_chars, (int) audio_data_length);
    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGW("whisper_full failed");
    }

    env->ReleaseStringUTFChars(language_str, language_chars);
    env->ReleaseStringUTFChars(prompt_str, prompt_chars);
    env->ReleaseStringUTFChars(grammar_str, grammar_chars);
    env->ReleaseFloatArrayElements(audio_data, audio_data_arr, JNI_ABORT);
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
    return env->NewStringUTF(text);
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
    return env->NewStringUTF(text);
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
    return env->NewStringUTF(whisper_print_system_info());
}

// Divide a latência em sample/encode/decode (ms) da última transcrição -- exibido na tela de
// diagnóstico (device de teste não tem logcat à mão) pra saber onde o tempo é gasto: p/ clip
// curto o encode costuma dominar, e é o que audio_ctx ataca. whisper_get_timings faz `new`,
// então delete aqui pra não vazar. Retorna [sample_ms, encode_ms, decode_ms].
JNIEXPORT jfloatArray JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTimings(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(thiz);
    struct whisper_timings *t = whisper_get_timings((struct whisper_context *) context_ptr);
    jfloat vals[3] = {0.0f, 0.0f, 0.0f};
    if (t != NULL) {
        vals[0] = t->sample_ms;
        vals[1] = t->encode_ms;
        vals[2] = t->decode_ms;
        delete t;
    }
    jfloatArray out = env->NewFloatArray(3);
    env->SetFloatArrayRegion(out, 0, 3, vals);
    return out;
}

} // extern "C"
