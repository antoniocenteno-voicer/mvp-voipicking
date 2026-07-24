package tech.voicer.voipicking.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Modelos STT que dá pra alternar em runtime pra A/B testar latência x compreensão no próprio
 * device (sem rebuild). Todos q8_0: no device de teste (arm64 sem dotprod/fp16) só o q8_0 tem
 * kernel genérico rápido — q5_1/q4_k medidos mais lentos. Trade-off medido no device real:
 *   - base-q8_0:  ~1.5-2s, encoder ~4x menor; compreensão mais fraca.
 *   - small-q8_0: ~6s (encoder ~2.8s é o piso nesse hardware); compreende bem melhor.
 * A gramática GBNF (mata alucinação fora-de-vocabulário) + pré-roll do VAD (recupera o ataque
 * da fala) atacam justamente a fraqueza do base — por isso vale A/B testar os dois com a MESMA
 * lógica antes de cravar um.
 */
enum class ModeloStt(val fileName: String, val rotulo: String) {
    BASE_Q8("ggml-base-q8_0.bin", "base (rápido)"),
    SMALL_Q8("ggml-small-q8_0.bin", "small (preciso)");

    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

/**
 * Baixa o modelo ggml selecionado p/ armazenamento privado do app no primeiro uso (por arquivo:
 * trocar de modelo baixa o novo e mantém o anterior em cache, então o A/B não re-baixa toda vez).
 * Não é embutido como asset: pesado demais p/ ficar no APK/repositório.
 */
object ModelManager {

    /** Modelo em uso. Alterável em runtime (ver PickingViewModel.trocarModelo). */
    @Volatile
    var modeloSelecionado: ModeloStt = ModeloStt.SMALL_Q8

    /** Nome do arquivo do modelo em uso — exibido na tela. */
    val nomeModeloAtual: String get() = modeloSelecionado.fileName

    fun modelFile(context: Context, modelo: ModeloStt = modeloSelecionado): File =
        File(File(context.filesDir, "models"), modelo.fileName)

    suspend fun ensureModel(context: Context, onProgress: (downloaded: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val modelo = modeloSelecionado
            val dest = modelFile(context, modelo)
            if (dest.exists() && dest.length() > 0) return@withContext dest

            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, "${modelo.fileName}.part")

            val connection = URL(modelo.url).openConnection() as HttpURLConnection
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
