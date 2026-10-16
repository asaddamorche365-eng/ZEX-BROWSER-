package com.zbrowser.data

import android.content.Context
import androidx.room.*

@Entity(tableName = "done_posts")
data class DonePost(
    @PrimaryKey val postId: String,
    val url: String,
    val author: String,
    val action: String,      // pending | reply | skip | failed | imported | own
    val project: String,
    val comment: String,
    val engine: String,
    val timestamp: Long
)

@Entity(tableName = "projects", indices = [Index(value = ["name"], unique = true)])
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val aliases: String,     // comma separated, lowercase
    val commentCount: Int,
    val lastSeen: Long
)

@Entity(tableName = "people")
data class Person(
    @PrimaryKey val username: String,
    val source: String,      // follow_list | import
    val status: String       // new | followed | skipped
)

@Entity(tableName = "rules")
data class Rule(
    @PrimaryKey val key: String, // rules | examples | banned | aliases | topics
    val value: String
)

@Dao
interface DoneDao {
    @Query("SELECT COUNT(*) FROM done_posts WHERE postId = :id")
    suspend fun has(id: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(p: DonePost): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(p: List<DonePost>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: DonePost)

    @Query("SELECT * FROM done_posts ORDER BY timestamp DESC LIMIT :n")
    suspend fun recent(n: Int): List<DonePost>

    @Query("SELECT COUNT(*) FROM done_posts")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM done_posts WHERE action = :a")
    suspend fun countAction(a: String): Int

    @Query("SELECT action FROM done_posts WHERE postId = :id")
    suspend fun actionOf(id: String): String?

    @Query("DELETE FROM done_posts WHERE postId = :id")
    suspend fun delete(id: String)

    // failed posts that were not already retried once
    @Query("SELECT * FROM done_posts WHERE action = 'failed' AND engine != 'retry' ORDER BY timestamp")
    suspend fun failedForRetry(): List<DonePost>

    @Query("SELECT comment FROM done_posts WHERE action = 'reply' ORDER BY timestamp DESC LIMIT :n")
    suspend fun recentComments(n: Int): List<String>

    // ---- History screen ----
    @Query("SELECT * FROM done_posts WHERE action NOT IN ('imported','own') ORDER BY timestamp DESC LIMIT :n")
    suspend fun historyAll(n: Int): List<DonePost>

    @Query("SELECT * FROM done_posts WHERE action = :a ORDER BY timestamp DESC LIMIT :n")
    suspend fun historyBy(a: String, n: Int): List<DonePost>

    @Query("SELECT * FROM done_posts WHERE action = 'pending' AND timestamp < :before ORDER BY timestamp DESC LIMIT :n")
    suspend fun historyStuck(before: Long, n: Int): List<DonePost>

    @Query("SELECT * FROM done_posts WHERE action = 'skip' AND comment = 'invalid model output' ORDER BY timestamp DESC LIMIT :n")
    suspend fun historyNoReply(n: Int): List<DonePost>

    @Query("SELECT COUNT(*) FROM done_posts WHERE action = 'pending' AND timestamp < :before")
    suspend fun countStuck(before: Long): Int

    @Query("SELECT COUNT(*) FROM done_posts WHERE action = 'skip' AND comment = 'invalid model output'")
    suspend fun countNoReply(): Int
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY commentCount DESC, name")
    suspend fun all(): List<Project>

    @Query("SELECT * FROM projects WHERE name = :n LIMIT 1")
    suspend fun byName(n: String): Project?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(p: Project): Long

    @Query("UPDATE projects SET commentCount = commentCount + 1, lastSeen = :t WHERE name = :n")
    suspend fun bump(n: String, t: Long)

    @Query("UPDATE projects SET aliases = :a WHERE name = :n")
    suspend fun setAliases(n: String, a: String)
}

@Dao
interface PeopleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(p: List<Person>)

    @Query("SELECT COUNT(*) FROM people")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM people WHERE username = :u")
    suspend fun has(u: String): Int

    @Query("SELECT username FROM people WHERE status = :s ORDER BY username LIMIT :n")
    suspend fun byStatus(s: String, n: Int): List<String>

    @Query("SELECT * FROM people ORDER BY username")
    suspend fun all(): List<Person>

    @Query("UPDATE people SET status = :s WHERE username = :u")
    suspend fun setStatus(u: String, s: String)
}

@Dao
interface RuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(r: Rule)

    @Query("SELECT value FROM rules WHERE `key` = :k")
    suspend fun get(k: String): String?

    @Query("SELECT `key` FROM rules WHERE `key` LIKE :prefix || '%' ORDER BY `key`")
    suspend fun keysWithPrefix(prefix: String): List<String>
}

@Database(entities = [DonePost::class, Project::class, Person::class, Rule::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun done(): DoneDao
    abstract fun projects(): ProjectDao
    abstract fun people(): PeopleDao
    abstract fun rules(): RuleDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "zbrowser.db").build().also { inst = it }
        }
    }
}
