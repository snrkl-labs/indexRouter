package org.snrkl.indexrouter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RoutingRuleEntity::class, RoutingLogEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun rules(): RoutingRuleDao
    abstract fun log(): RoutingLogDao

    companion object {

        /**
         * Adds the two columns the folder schema needs. Written rather than letting Room
         * fall back to destructive recreation, which would wipe the routing log —
         * the only record that timer and calendar routes ever happened.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routing_rules ADD COLUMN includeInMoc INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE routing_log ADD COLUMN manuallyRouted INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * One keyword per rule becomes many.
         *
         * The table is rebuilt rather than altered, because the old unique index
         * on `keyword` has to go: uniqueness now spans every rule's keywords
         * together, which no single-column index can express. It is checked
         * before writing instead, where a clash can be reported rather than
         * thrown — that index throwing is what crashed the app in v0.4.0.
         *
         * Existing rules carry over with their one keyword as a single-item set,
         * so nothing is lost.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE routing_rules_new (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "keywords TEXT NOT NULL, " +
                        "action TEXT NOT NULL, " +
                        "folder TEXT, " +
                        "tags TEXT NOT NULL, " +
                        "matchType TEXT NOT NULL DEFAULT 'contains_word', " +
                        "priority INTEGER NOT NULL DEFAULT 100, " +
                        "enabled INTEGER NOT NULL DEFAULT 1, " +
                        "includeInMoc INTEGER NOT NULL DEFAULT 1, " +
                        "updatedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO routing_rules_new " +
                        "(id, keywords, action, folder, tags, matchType, priority, enabled, includeInMoc, updatedAt) " +
                        "SELECT id, keyword, action, folder, tags, matchType, priority, enabled, includeInMoc, updatedAt " +
                        "FROM routing_rules",
                )
                db.execSQL("DROP TABLE routing_rules")
                db.execSQL("ALTER TABLE routing_rules_new RENAME TO routing_rules")
            }
        }

        /** Records how long each recording took, so a stall can be measured. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routing_log ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * The per-rule strip switch, and the two columns the log needs to
         * support it and the retention rule.
         *
         * `stripKeyword` defaults to 1 so existing rules keep stripping — and
         * so file rules, which never did, begin to. That is the intent: the
         * switch is what decides now, and on is the answer that matches every
         * existing rule.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routing_rules ADD COLUMN stripKeyword INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE routing_log ADD COLUMN keywordStripped INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE routing_log ADD COLUMN isMarker INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "indexrouter.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
