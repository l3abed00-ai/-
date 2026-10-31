package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [JadhadhaEntity::class, AttachmentEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun jadhadhaDao(): JadhadhaDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jadhadha_table ADD COLUMN category TEXT NOT NULL DEFAULT 'نصوص قرائية'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jadhadha_table ADD COLUMN infoReference TEXT NOT NULL DEFAULT 'المختار في اللغة العربية'")
                db.execSQL("ALTER TABLE jadhadha_table ADD COLUMN infoTeacherName TEXT NOT NULL DEFAULT 'محمد العابد'")
                db.execSQL("ALTER TABLE jadhadha_table ADD COLUMN infoSchoolName TEXT NOT NULL DEFAULT 'مدرسة أنوال (السلك الإعدادي)'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jadhadha_table ADD COLUMN isReviewed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE jadhadha_table ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attachments_table (
                        id TEXT NOT NULL PRIMARY KEY,
                        planId TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        fileSize INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(planId) REFERENCES jadhadha_table(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_table_planId ON attachments_table(planId)")
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5
        )

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "jadhadha_local_database.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
