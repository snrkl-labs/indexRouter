package org.snrkl.indexrouter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutingRuleDao {

    @Query("SELECT * FROM routing_rules ORDER BY priority ASC, id ASC")
    fun observeAll(): Flow<List<RoutingRuleEntity>>

    @Query("SELECT * FROM routing_rules ORDER BY priority ASC, id ASC")
    suspend fun all(): List<RoutingRuleEntity>

    @Query("SELECT COUNT(*) FROM routing_rules")
    suspend fun count(): Int

    /**
     * Every rule, for the duplicate check. A keyword now belongs to a set, so a
     * clash cannot be found with a WHERE clause on one column.
     */
    @Query("SELECT * FROM routing_rules")
    suspend fun allForValidation(): List<RoutingRuleEntity>

    @Upsert
    suspend fun upsert(rule: RoutingRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<RoutingRuleEntity>)

    @Query("DELETE FROM routing_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM routing_rules")
    suspend fun clear()
}

@Dao
interface RoutingLogDao {

    @Query("SELECT * FROM routing_log ORDER BY receivedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<RoutingLogEntity>>

    @Insert
    suspend fun insert(entry: RoutingLogEntity): Long

    /**
     * Records a manual correction. `matchedKeyword` is deliberately left alone —
     * overwriting it would erase what the classifier originally decided, which is
     * the only way to answer "what does it keep getting wrong".
     */
    @Query(
        "UPDATE routing_log SET manuallyRouted = 1, action = :action, targetPath = :targetPath " +
            "WHERE id = :id",
    )
    suspend fun markRerouted(id: Long, action: String, targetPath: String)

    @Query("SELECT * FROM routing_log WHERE id = :id")
    suspend fun byId(id: Long): RoutingLogEntity?

    /**
     * The most recent **dictations**, newest first — what the voice input method
     * offers the keyboard.
     *
     * Narrowed to the dictation route on purpose, and it is a safety property
     * rather than tidiness: this text is committed into whatever field happens
     * to have focus. Reading every recording meant a spoken shopping list or a
     * private note could land in a work email. Only what was deliberately
     * marked "this is for typing" is offered.
     *
     * Read from the log rather than a second store: every recording already
     * lands here with its text and arrival time, and a parallel buffer could
     * only ever disagree with it. Blank transcripts are skipped, since
     * committing an empty string is worse than committing nothing.
     */
    @Query(
        "SELECT * FROM routing_log WHERE TRIM(transcript) != '' AND action = :action " +
            "ORDER BY receivedAt DESC LIMIT :limit",
    )
    suspend fun recentTranscripts(action: String, limit: Int): List<RoutingLogEntity>

    @Query("DELETE FROM routing_log")
    suspend fun clear()

    /** Everything but the markers, which are the record that clearing happened. */
    @Query("DELETE FROM routing_log WHERE isMarker = 0")
    suspend fun clearContent(): Int

    /**
     * Prunes content older than the cutoff. Markers are left alone: they hold
     * no message content and are the only evidence the rest was ever there.
     */
    @Query("DELETE FROM routing_log WHERE isMarker = 0 AND receivedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /** Whether the newest row is already a marker, so clearing twice adds one line, not two. */
    @Query("SELECT isMarker FROM routing_log ORDER BY receivedAt DESC LIMIT 1")
    suspend fun newestIsMarker(): Boolean?

    /**
     * Drops every marker, for the moment a clear leaves nothing else behind.
     *
     * A marker means "what was before this point is gone". Two of them with no
     * content in between says that twice and dates it wrongly, so the old one
     * goes and a single fresh marker carries the whole truth.
     */
    @Query("DELETE FROM routing_log WHERE isMarker = 1")
    suspend fun clearMarkers(): Int

    /**
     * The same collapse for the retention rule: once the content either side of
     * a marker has aged out, the marker is describing a removal that is itself
     * outside the window being kept.
     */
    @Query("DELETE FROM routing_log WHERE isMarker = 1 AND receivedAt < :cutoff")
    suspend fun deleteMarkersOlderThan(cutoff: Long): Int
}
