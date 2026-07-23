package tech.voicer.voipicking

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.voicer.voipicking.data.db.AppDatabase
import tech.voicer.voipicking.repository.PedidoRepository
import tech.voicer.voipicking.state.PickingState
import tech.voicer.voipicking.ui.PickingViewModel
import tech.voicer.voipicking.ui.SttFase
import tech.voicer.voipicking.voice.TtsManager
import tech.voicer.voipicking.voice.WhisperSttEngine

class MainActivity : ComponentActivity() {

    private val pedirPermissaoAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* resultado tratado via checagem no onResume da tela */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pedirPermissaoAudio.launch(Manifest.permission.RECORD_AUDIO)
        }

        val db = AppDatabase.obter(applicationContext)
        val repository = PedidoRepository(db.pedidoDao(), db.separacaoDao())
        val tts = TtsManager(applicationContext)
        val sttEngine = WhisperSttEngine()

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PickingViewModel(application, repository, tts, sttEngine) as T
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: PickingViewModel = viewModel(factory = factory)
                    LaunchedEffect(Unit) { viewModel.prepararStt() }
                    TelaPicking(viewModel)
                }
            }
        }
    }
}

@Composable
fun TelaPicking(viewModel: PickingViewModel) {
    val estado by viewModel.estado.collectAsState()
    val sttFase by viewModel.sttFase.collectAsState()
    val sttMensagem by viewModel.sttMensagem.collectAsState()
    val tarefaExemplo = remember { TAREFA_EXEMPLO_JSON }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Motor de voz: $sttMensagem", style = MaterialTheme.typography.labelMedium)
        Text("Estado atual:", style = MaterialTheme.typography.labelLarge)
        Text(descreverEstado(estado), style = MaterialTheme.typography.bodyLarge)

        when (estado) {
            is PickingState.Ocioso ->
                Button(onClick = { viewModel.carregarTarefa(tarefaExemplo) }) {
                    Text("Carregar tarefa de exemplo")
                }
            is PickingState.AguardandoConfirmacaoEndereco -> {
                BotaoMicrofone(sttFase, viewModel)
                Button(onClick = { viewModel.onFalaConfirmacaoEndereco("confirmado") }) { Text("(teste) confirmar endereço") }
                Button(onClick = { viewModel.onFalaConfirmacaoEndereco("repete") }) { Text("(teste) repetir") }
            }
            is PickingState.AguardandoConfirmacaoColeta -> {
                BotaoMicrofone(sttFase, viewModel)
                Button(onClick = { viewModel.onFalaConfirmacaoColeta("confirmado") }) { Text("(teste) confirmar coleta") }
                Button(onClick = { viewModel.onFalaConfirmacaoColeta("divergencia", null) }) { Text("(teste) reportar divergência") }
            }
            is PickingState.DivergenciaReportada ->
                Button(onClick = { viewModel.confirmarDivergencia() }) { Text("Ok, seguir") }
            is PickingState.AnunciandoEndereco ->
                Button(onClick = { viewModel.onEnderecoAnunciadoConcluido() }) { Text("(TTS falando endereço...)") }
            is PickingState.AnunciandoProduto ->
                Button(onClick = { viewModel.onProdutoAnunciadoConcluido() }) { Text("(TTS falando produto...)") }
            is PickingState.TarefaConcluida ->
                Text("Tarefa concluída!")
            is PickingState.Erro ->
                Button(onClick = { viewModel.cancelar() }) { Text("Cancelar / reiniciar") }
            else -> {}
        }
    }
}

@Composable
private fun BotaoMicrofone(sttFase: SttFase, viewModel: PickingViewModel) {
    when (sttFase) {
        SttFase.PRONTO ->
            Button(onClick = { viewModel.iniciarGravacao() }) { Text("🎤 Gravar resposta") }
        SttFase.GRAVANDO ->
            Button(onClick = { viewModel.pararGravacaoEAvaliar() }) { Text("Parar e enviar") }
        SttFase.TRANSCREVENDO ->
            Text("Reconhecendo fala...")
        SttFase.BAIXANDO_MODELO, SttFase.CARREGANDO_MOTOR ->
            Text("Preparando motor de voz...")
        SttFase.NAO_INICIALIZADO, SttFase.ERRO ->
            Text("Motor de voz indisponível — use os botões manuais abaixo se precisar testar sem microfone.")
    }
}

private fun descreverEstado(estado: PickingState): String = when (estado) {
    is PickingState.Ocioso -> "Ocioso — nenhuma tarefa carregada"
    is PickingState.TarefaCarregada -> "Tarefa ${estado.tarefa.id} carregada"
    is PickingState.AnunciandoEndereco -> "Anunciando endereço ${estado.item.endereco.formatado}"
    is PickingState.AguardandoConfirmacaoEndereco -> "Aguardando confirmação do endereço ${estado.item.endereco.formatado}"
    is PickingState.AnunciandoProduto -> "Anunciando produto ${estado.item.produto.nome}"
    is PickingState.AguardandoConfirmacaoColeta -> "Aguardando confirmação de coleta de ${estado.item.produto.nome}"
    is PickingState.DivergenciaReportada -> "Divergência: ${estado.quantidadeInformada} unidades"
    is PickingState.ItemConcluido -> "Item ${estado.item.sequencia} concluído"
    is PickingState.TarefaConcluida -> "Tarefa ${estado.tarefa.id} concluída"
    is PickingState.Erro -> "Erro: ${estado.motivo}"
}

/** Tarefa reduzida pra teste manual em tela — mesmo formato de docs/EP_2026_000142.json. */
private const val TAREFA_EXEMPLO_JSON = """
{
  "tarefa": {
    "id": "SEP-2026-000142",
    "tipo": "SEPARACAO",
    "centroDistribuicao": { "codigo": "CD-SP-01", "nome": "Centro de Distribuição São Paulo" },
    "pedido": { "numero": "PED-2026-008731", "destino": "Loja 025 - Campinas" },
    "status": "PENDENTE",
    "totalEnderecos": 2,
    "totalUnidades": 12,
    "enderecos": [
      {
        "sequencia": 1,
        "endereco": { "setor": "01", "rua": "03", "box": "012", "altura": "01", "caixa": "04", "enderecoFormatado": "01-03-012-01-04", "digitoVerificacao": "317" },
        "produto": { "codigoInterno": "PROD-0001", "nome": "Arroz Tipo 1", "descricao": "Arroz branco Tipo 1, pacote de 5 kg", "codigoBarras": "7896006711117" },
        "quantidadeSolicitada": 4,
        "unidade": "UN"
      },
      {
        "sequencia": 2,
        "endereco": { "setor": "01", "rua": "03", "box": "018", "altura": "02", "caixa": null, "enderecoFormatado": "01-03-018-02", "digitoVerificacao": "842" },
        "produto": { "codigoInterno": "PROD-0002", "nome": "Feijão Carioca", "descricao": "Feijão carioca Tipo 1, pacote de 1 kg", "codigoBarras": "7896006711124" },
        "quantidadeSolicitada": 8,
        "unidade": "UN"
      }
    ]
  }
}
"""
