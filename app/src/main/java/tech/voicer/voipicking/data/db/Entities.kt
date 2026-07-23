package tech.voicer.voipicking.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pedidos")
data class PedidoEntity(
    @PrimaryKey val tarefaId: String,
    val pedidoNumero: String,
    val cdCodigo: String,
    val cdNome: String,
    val destino: String,
    val status: String,
    val totalEnderecos: Int,
    val totalUnidades: Int,
    val criadoEm: Long
)

@Entity(tableName = "endereco_itens")
data class EnderecoItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tarefaId: String,
    val sequencia: Int,
    val setor: String,
    val rua: String,
    val box: String,
    val altura: String,
    val caixa: String?,
    val enderecoFormatado: String,
    val digitoVerificacao: String,
    val produtoCodigoInterno: String,
    val produtoNome: String,
    val produtoDescricao: String,
    val produtoCodigoBarras: String,
    val quantidadeSolicitada: Int,
    val unidade: String
)

@Entity(tableName = "separacoes_executadas")
data class SeparacaoExecutadaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tarefaId: String,
    val enderecoItemId: Long,
    val quantidadeSeparada: Int,
    val confirmadoPorVoz: Boolean,
    val transcricaoConfirmacao: String?,
    val timestamp: Long,
    val status: String
)

@Entity(tableName = "config")
data class ConfigEntity(
    @PrimaryKey val chave: String,
    val valor: String
)
