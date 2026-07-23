package tech.voicer.voipicking.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PedidoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun inserirPedido(pedido: PedidoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun inserirEnderecos(itens: List<EnderecoItemEntity>)

    @Query("SELECT * FROM pedidos WHERE tarefaId = :tarefaId")
    suspend fun buscarPedido(tarefaId: String): PedidoEntity?

    @Query("SELECT * FROM endereco_itens WHERE tarefaId = :tarefaId ORDER BY sequencia ASC")
    suspend fun buscarEnderecos(tarefaId: String): List<EnderecoItemEntity>

    @Query("SELECT * FROM pedidos ORDER BY criadoEm DESC")
    fun observarPedidos(): Flow<List<PedidoEntity>>

    @Query("UPDATE pedidos SET status = :status WHERE tarefaId = :tarefaId")
    suspend fun atualizarStatusPedido(tarefaId: String, status: String)
}

@Dao
interface SeparacaoDao {
    @Insert
    suspend fun registrarSeparacao(item: SeparacaoExecutadaEntity): Long

    @Query("SELECT * FROM separacoes_executadas WHERE tarefaId = :tarefaId ORDER BY timestamp ASC")
    suspend fun buscarSeparacoesPorTarefa(tarefaId: String): List<SeparacaoExecutadaEntity>
}

@Dao
interface ConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun definir(config: ConfigEntity)

    @Query("SELECT valor FROM config WHERE chave = :chave")
    suspend fun obter(chave: String): String?

    @Query("SELECT * FROM config")
    fun observarTudo(): Flow<List<ConfigEntity>>
}
