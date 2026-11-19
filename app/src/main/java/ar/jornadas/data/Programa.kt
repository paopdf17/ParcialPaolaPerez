package ar.jornadas.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import retrofit2.http.GET
import kotlinx.coroutines.flow.Flow

data class FichaRemota(val id: String, val title: String, val track: String, val day: Int,
    val time: String, val room: String, val speaker: String, val summary: String)

interface ProgramaApi {
    @GET("programa") suspend fun consultar(): List<FichaRemota>
}

@Entity(tableName = "charlas")
data class Charla(@PrimaryKey val id: String, val titulo: String, val tema: String, val dia: Int,
    val hora: String, val sala: String, val ponente: String, val resumen: String,
    val favorita: Boolean = false)

/** Su sola existencia indica que ya se descargó una edición (aunque venga vacía). */
@Entity(tableName = "edicion")
data class Edicion(@PrimaryKey val id: Int = 1)

@Dao
interface CarteleraDao {
    @Query("SELECT * FROM charlas ORDER BY dia, hora, sala")
    fun observar(): Flow<List<Charla>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(fichas: List<Charla>)

    @Query("UPDATE charlas SET favorita = NOT favorita WHERE id = :id")
    suspend fun alternar(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM edicion)")
    fun hayEdicion(): Flow<Boolean>

    @Query("SELECT id FROM charlas WHERE favorita = 1")
    suspend fun marcadas(): List<String>

    @Query("DELETE FROM charlas WHERE id NOT IN (:ids)")
    suspend fun borrarFueraDe(ids: List<String>)

    @Query("DELETE FROM charlas")
    suspend fun borrarTodas()

    @Query("INSERT OR REPLACE INTO edicion (id) VALUES (1)")
    suspend fun registrarEdicion()

    /**
     * La edición recibida es una instantánea completa: lo que no viene se retiró.
     * Todo ocurre en una sola transacción y las marcas se leen al momento de escribir,
     * así se respeta la que el usuario cambió mientras corría la petición.
     */
    @Transaction
    suspend fun reemplazarPrograma(fichas: List<Charla>) {
        val marcadas = marcadas().toSet()
        if (fichas.isEmpty()) borrarTodas() else borrarFueraDe(fichas.map { it.id })
        guardar(fichas.map { it.copy(favorita = it.id in marcadas) })
        registrarEdicion()
    }
}

@Database(entities = [Charla::class, Edicion::class], version = 2, exportSchema = false)
abstract class Archivo : RoomDatabase() {
    abstract fun cartelera(): CarteleraDao
}

/** Conserva charlas y marcas; si ya había charlas guardadas, cuenta como edición descargada. */
val MIGRACION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `edicion` (`id` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("INSERT INTO edicion (id) SELECT 1 WHERE EXISTS (SELECT 1 FROM charlas)")
    }
}
