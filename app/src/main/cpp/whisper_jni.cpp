// Ponte JNI entre WhisperSttEngine.kt e whisper.cpp.
// Em modo stub (submódulo whisper.cpp ausente), retorna handle inválido /
// texto vazio — permite buildar e testar o resto do app sem o modelo nativo.
#include <jni.h>
#include <string>
#include <vector>

#ifdef VOX_WHISPER_DISPONIVEL
#include "whisper.h"
#endif

extern "C" JNIEXPORT jlong JNICALL
Java_tech_voicer_voipicking_voice_WhisperSttEngine_nativeCarregarModelo(
        JNIEnv *env, jobject /*thiz*/, jstring caminhoModelo) {
#ifdef VOX_WHISPER_DISPONIVEL
    const char *caminho = env->GetStringUTFChars(caminhoModelo, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(caminho, cparams);
    env->ReleaseStringUTFChars(caminhoModelo, caminho);
    return reinterpret_cast<jlong>(ctx);
#else
    (void) env; (void) caminhoModelo;
    return 0L;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_tech_voicer_voipicking_voice_WhisperSttEngine_nativeTranscrever(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jshortArray pcm16k) {
#ifdef VOX_WHISPER_DISPONIVEL
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");

    jsize n = env->GetArrayLength(pcm16k);
    jshort *amostras = env->GetShortArrayElements(pcm16k, nullptr);

    std::vector<float> pcmFloat(n);
    for (jsize i = 0; i < n; i++) {
        pcmFloat[i] = static_cast<float>(amostras[i]) / 32768.0f;
    }
    env->ReleaseShortArrayElements(pcm16k, amostras, JNI_ABORT);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.language = "pt";
    wparams.print_progress = false;
    wparams.print_special = false;

    if (whisper_full(ctx, wparams, pcmFloat.data(), static_cast<int>(pcmFloat.size())) != 0) {
        return env->NewStringUTF("");
    }

    std::string resultado;
    int n_segmentos = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segmentos; i++) {
        resultado += whisper_full_get_segment_text(ctx, i);
    }
    return env->NewStringUTF(resultado.c_str());
#else
    (void) env; (void) handle; (void) pcm16k;
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_tech_voicer_voipicking_voice_WhisperSttEngine_nativeLiberar(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
#ifdef VOX_WHISPER_DISPONIVEL
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
#else
    (void) handle;
#endif
}
