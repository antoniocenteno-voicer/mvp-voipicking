package tech.voicer.voipicking.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PedidoEnvelope(
    val tarefa: Tarefa
)

@Serializable
data class Tarefa(
    val id: String,
    val tipo: String,
    val centroDistribuicao: CentroDistribuicao,
    val pedido: PedidoInfo,
    val status: String,
    val totalEnderecos: Int,
    val totalUnidades: Int,
    val enderecos: List<EnderecoItem>
)

@Serializable
data class CentroDistribuicao(
    val codigo: String,
    val nome: String
)

@Serializable
data class PedidoInfo(
    val numero: String,
    val destino: String
)

@Serializable
data class EnderecoItem(
    val sequencia: Int,
    val endereco: Endereco,
    val produto: Produto,
    val quantidadeSolicitada: Int,
    val unidade: String
)

@Serializable
data class Endereco(
    val setor: String,
    val rua: String,
    val box: String,
    val altura: String,
    val caixa: String? = null,
    @SerialName("enderecoFormatado") val formatado: String,
    val digitoVerificacao: String
)

@Serializable
data class Produto(
    val codigoInterno: String,
    val nome: String,
    val descricao: String,
    val codigoBarras: String
)
