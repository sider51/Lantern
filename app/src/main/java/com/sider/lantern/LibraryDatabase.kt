package com.sider.lantern

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

internal const val ROOT_ID = "root"
internal const val INBOX_ID = "inbox"

@Entity(
    tableName = "folders",
    foreignKeys = [ForeignKey(
        entity = FolderRow::class, parentColumns = ["id"], childColumns = ["parentId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["parentId", "name"], unique = true)],
)
@Serializable
internal data class FolderRow(
    @PrimaryKey val id: String,
    val parentId: String?,
    val name: String,
)

@Entity(
    tableName = "manuscripts",
    foreignKeys = [ForeignKey(
        entity = FolderRow::class, parentColumns = ["id"], childColumns = ["folderId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("folderId"), Index("updatedAt")],
)
internal data class ManuscriptRow(
    @PrimaryKey val id: String,
    val folderId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
    val schemaVersion: Int,
    val title: String,
    val body: String,
    val formatting: String,
    val characterCount: Int,
)

internal data class ManuscriptSummary(
    val id: String,
    val folderId: String,
    val folderName: String,
    val updatedAt: Long,
    val title: String,
    val preview: String,
    val characterCount: Int,
)

@Entity(tableName = "trash")
@Serializable
internal data class TrashRow(@PrimaryKey val id: String, val name: String, val removedAt: Long, val payload: String)

@Dao
internal interface LibraryDao {
    @Query("SELECT * FROM folders")
    suspend fun allFolders(): List<FolderRow>
    @Query("SELECT * FROM manuscripts")
    suspend fun allManuscripts(): List<ManuscriptRow>
    @Query("DELETE FROM manuscripts WHERE id = :id")
    suspend fun deleteManuscript(id: String)
    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolder(id: String)
    @Query("SELECT * FROM trash ORDER BY removedAt DESC")
    fun observeTrash(): Flow<List<TrashRow>>
    @Query("SELECT * FROM trash")
    suspend fun allTrash(): List<TrashRow>
    @Query("SELECT * FROM trash WHERE id = :id")
    suspend fun trash(id: String): TrashRow?
    @Insert
    suspend fun insertTrash(row: TrashRow)
    @Query("DELETE FROM trash WHERE id = :id")
    suspend fun deleteTrash(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seedFolder(folder: FolderRow)

    @Insert
    suspend fun insertFolder(folder: FolderRow)

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE, id")
    fun observeFolders(): Flow<List<FolderRow>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun folder(id: String): FolderRow?

    @Query("SELECT EXISTS(SELECT 1 FROM folders WHERE parentId = :parentId AND name = :name AND id != :exceptId)")
    suspend fun folderExists(parentId: String, name: String, exceptId: String): Boolean

    @Query("UPDATE folders SET name = :name, parentId = :parentId WHERE id = :id AND id NOT IN ('root','inbox')")
    suspend fun updateFolder(id: String, name: String, parentId: String): Int

    @Insert
    suspend fun insertManuscript(row: ManuscriptRow)

    @Query("SELECT * FROM manuscripts WHERE id = :id")
    suspend fun manuscript(id: String): ManuscriptRow?

    @Query("""
        UPDATE manuscripts SET title = :title, body = :body, formatting = :formatting,
            schemaVersion = :schemaVersion, characterCount = :characterCount,
            updatedAt = MAX(updatedAt, :updatedAt), revision = :revision
        WHERE id = :id AND revision = :expected
    """)
    suspend fun save(
        id: String, expected: Long, revision: Long, title: String, body: String,
        formatting: String, schemaVersion: Int, characterCount: Int, updatedAt: Long,
    ): Int

    @Query("""
        UPDATE manuscripts SET folderId = :folderId, revision = revision + 1,
            updatedAt = MAX(updatedAt, :updatedAt)
        WHERE id = :id AND folderId != :folderId
    """)
    suspend fun moveManuscript(id: String, folderId: String, updatedAt: Long): Int

    @Query("""
        SELECT m.id, m.folderId, f.name AS folderName, m.updatedAt, m.title,
            substr(m.body, 1, 100) AS preview, m.characterCount
        FROM manuscripts m INNER JOIN folders f ON m.folderId = f.id
        WHERE (:folderId IS NULL OR m.folderId = :folderId)
          AND (:query = '' OR instr(lower(m.title), lower(:query)) > 0 OR instr(lower(m.body), lower(:query)) > 0)
        ORDER BY CASE WHEN :byTitle AND m.title = '' THEN 1 ELSE 0 END,
            CASE WHEN :byTitle AND NOT :descending THEN m.title END COLLATE NOCASE ASC,
            CASE WHEN :byTitle AND :descending THEN m.title END COLLATE NOCASE DESC,
            CASE WHEN NOT :byTitle AND NOT :descending THEN m.updatedAt END ASC,
            m.updatedAt DESC, m.id
        LIMIT :limit
    """)
    fun observeManuscripts(folderId: String?, query: String, byTitle: Boolean, descending: Boolean, limit: Int): Flow<List<ManuscriptSummary>>
}

@Database(entities = [FolderRow::class, ManuscriptRow::class, TrashRow::class], version = 2, exportSchema = true)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        fun open(context: Context, name: String = "lantern.db"): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(
                object : Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS trash (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, removedAt INTEGER NOT NULL, payload TEXT NOT NULL)")
                    }
                },
            ).build()
    }
}
