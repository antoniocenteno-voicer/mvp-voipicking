package tech.voicer.voipicking.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Histórico do teste latência x compreensão (ver conversa sobre "três"/"treze" e
// "dezessete"/"dezesseis"): "ggml-base-q8_0.bin" (~1.2s/frase) errou a confirmação de
// endereço 3x seguidas; "ggml-small-q8_0.bin" (~4-5s emulador / ~10s no device real de
// teste, sem dotprod/fp16 confirmado via tela de diagnóstico) acertou de primeira, mas
// muito lento nesse hardware; "ggml-small-q5_1.bin" e "ggml-small-q4_0.bin" descartados
// (o primeiro pior ainda, o segundo nem existe pra esse tamanho). A confusão "três"/"treze"
// etc. passou a ser tratada na camada de matching (ver PortugueseNumberParser.candidatosDigitos).
//
// Testando agora "ggml-small-q4_k.bin": requantizado por nós localmente (não existe
// publicado oficialmente) a partir do "ggml-small.bin" f16 oficial, usando o
// whisper-quantize vendorizado em third_party/whisper.cpp. K-quants (q4_K/q5_K/q6_K) são
// uma família mais moderna que os legados q4_0/q5_1, com kernel genérico geralmente melhor
// otimizado — candidato a meio-termo real entre base (rápido/impreciso) e small-q8_0
// (preciso/lento). 145MB, contra ~250MB do small-q8_0. Hospedado numa GitHub Release deste
// repo (github.com/antoniocenteno-voicer/mvp-voipicking/releases/tag/stt-models-v1), já que
// não existe publicação oficial — baixa sozinho no primeiro acesso, igual aos outros.
private const val MODEL_FILE_NAME = "ggml-small-q4_k.bin"
private const val MODEL_URL =
    "https://github.com/antoniocenteno-voicer/mvp-voipicking/releases/download/stt-models-v1/$MODEL_FILE_NAME"

/**
 * Baixa o modelo multilíngue ggml p/ armazenamento privado do app no primeiro uso. Não é
 * embutido como asset: pesado demais p/ ficar no APK/repositório.
 * q8_0 (quantização 8-bit, build oficial whisper.cpp): reduz o custo de largura de
 * banda de memória que domina o matmul em CPU mobile, com a menor perda de
 * qualidade entre as quantizações disponíveis.
 */
object ModelManager {

    /** Nome do modelo em uso — exibido na tela pra saber qual variante está sendo testada. */
    val nomeModeloAtual: String get() = MODEL_FILE_NAME

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), MODEL_FILE_NAME)

    suspend fun ensureModel(context: Context, onProgress: (downloaded: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dest = modelFile(context)
            if (dest.exists() && dest.length() > 0) return@withContext dest

            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, "$MODEL_FILE_NAME.part")

            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "download falhou: HTTP ${connection.responseCode}"
            }
            val total = connection.contentLengthLong

            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            tmp.renameTo(dest)
            dest
        }
}
