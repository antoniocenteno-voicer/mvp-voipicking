package tech.voicer.voipicking.repository

import tech.voicer.voipicking.data.db.EnderecoItemEntity
import tech.voicer.voipicking.data.db.PedidoDao
import tech.voicer.voipicking.data.db.PedidoEntity
import tech.voicer.voipicking.data.db.SeparacaoDao
import tech.voicer.voipicking.data.db.SeparacaoExecutadaEntity
import tech.voicer.voipicking.data.model.Tarefa

class PedidoRepository(
    private val pedidoDao: PedidoDao,
    private val separacaoDao: SeparacaoDao
) {

    suspend fun persistirTarefa(tarefa: Tarefa, agora: Long) {
        pedidoDao.inserirPedido(
            PedidoEntity(
                tarefaId = tarefa.id,
                pedidoNumero = tarefa.pedido.numero,
                cdCodigo = tarefa.centroDistribuicao.codigo,
                cdNome = tarefa.centroDistribuicao.nome,
                destino = tarefa.pedido.destino,
                status = tarefa.status,
                totalEnderecos = tarefa.totalEnderecos,
                totalUnidades = tarefa.totalUnidades,
                criadoEm = agora
            )
        )
        pedidoDao.inserirEnderecos(
            tarefa.enderecos.map { item ->
                EnderecoItemEntity(
                    tarefaId = tarefa.id,
                    sequencia = item.sequencia,
                    setor = item.endereco.setor,
                    rua = item.endereco.rua,
                    box = item.endereco.box,
                    altura = item.endereco.altura,
                    caixa = item.endereco.caixa,
                    enderecoFormatado = item.endereco.formatado,
                    digitoVerificacao = item.endereco.digitoVerificacao,
                    produtoCodigoInterno = item.produto.codigoInterno,
                    produtoNome = item.produto.nome,
                    produtoDescricao = item.produto.descricao,
                    produtoCodigoBarras = item.produto.codigoBarras,
                    quantidadeSolicitada = item.quantidadeSolicitada,
                    unidade = item.unidade
                )
            }
        )
    }

    suspend fun registrarSeparacao(
        tarefaId: String,
        enderecoItemId: Long,
        quantidadeSeparada: Int,
        confirmadoPorVoz: Boolean,
        transcricao: String?,
        agora: Long,
        status: String
    ): Long = separacaoDao.registrarSeparacao(
        SeparacaoExecutadaEntity(
            tarefaId = tarefaId,
            enderecoItemId = enderecoItemId,
            quantidadeSeparada = quantidadeSeparada,
            confirmadoPorVoz = confirmadoPorVoz,
            transcricaoConfirmacao = transcricao,
            timestamp = agora,
            status = status
        )
    )

    suspend fun finalizarTarefa(tarefaId: String) {
        pedidoDao.atualizarStatusPedido(tarefaId, "CONCLUIDO")
    }
}
