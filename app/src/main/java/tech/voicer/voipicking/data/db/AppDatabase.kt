package tech.voicer.voipicking.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PedidoEntity::class,
        EnderecoItemEntity::class,
        SeparacaoExecutadaEntity::class,
        ConfigEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pedidoDao(): PedidoDao
    abstract fun separacaoDao(): SeparacaoDao
    abstract fun configDao(): ConfigDao

    companion object {
        @Volatile private var instancia: AppDatabase? = null

        fun obter(context: Context): AppDatabase =
            instancia ?: synchronized(this) {
                instancia ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vox_picking.db"
                ).build().also { instancia = it }
            }
    }
}
