package tech.voicer.voipicking.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val MODEL_FILE_NAME = "ggml-base-q8_0.bin"
private const val MODEL_URL =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL_FILE_NAME"

/**
 * Baixa o modelo multilíngue ggml-base p/ armazenamento privado do app no primeiro
 * uso. Não é embutido como asset: pesado demais p/ ficar no APK/repositório.
 * q8_0 (quantização 8-bit, build oficial whisper.cpp): reduz o custo de largura de
 * banda de memória que domina o matmul em CPU mobile, com a menor perda de
 * qualidade entre as quantizações disponíveis.
 */
object ModelManager {

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
