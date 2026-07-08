package com.speedevand.inkride.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Historical schema JSON for versions 4/5 was never exported (exportSchema
 * was false until this change), so Room's MigrationTestHelper — which
 * requires a schema snapshot of the *starting* version — can't validate
 * MIGRATION_4_5/MIGRATION_5_6 retroactively. These tests hand-build just the
 * columns each migration's SQL reads or writes and run the Migration object
 * directly against a real (Robolectric-backed) SQLite database instead.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun openHelper(
        dbName: String,
        version: Int,
        createSql: List<String>,
    ): SupportSQLiteOpenHelper {
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createSql.forEach { db.execSQL(it) }
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    @Test
    fun `migration 4 to 5 adds ride_lap table and paired-address columns`() {
        val db =
            openHelper(
                dbName = "migration_4_5_test",
                version = 4,
                createSql =
                    listOf(
                        "CREATE TABLE `user_settings` (`id` INTEGER PRIMARY KEY NOT NULL, `weightKg` INTEGER NOT NULL)",
                        "INSERT INTO `user_settings` (`id`, `weightKg`) VALUES (1, 75)",
                    ),
            ).writableDatabase

        MIGRATION_4_5.migrate(db)

        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='ride_lap'").use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
        }
        db.query("SELECT weightKg, pairedHrmAddress, pairedCadenceAddress FROM user_settings WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(75)
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.isNull(2)).isTrue()
        }
        db.close()
    }

    @Test
    fun `migration 5 to 6 seeds a default bike profile from the flat settings columns`() {
        val db =
            openHelper(
                dbName = "migration_5_6_test",
                version = 5,
                createSql =
                    listOf(
                        "CREATE TABLE `user_settings` (`id` INTEGER PRIMARY KEY NOT NULL, `bikeWeightKg` REAL NOT NULL, `bikeType` TEXT NOT NULL)",
                        "INSERT INTO `user_settings` (`id`, `bikeWeightKg`, `bikeType`) VALUES (1, 12.5, 'GRAVEL')",
                    ),
            ).writableDatabase

        MIGRATION_5_6.migrate(db)

        var seededProfileId = -1L
        db.query("SELECT id, name, weightKg, type FROM bike_profile").use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
            assertThat(cursor.moveToFirst()).isTrue()
            seededProfileId = cursor.getLong(0)
            assertThat(cursor.getString(1)).isEqualTo("Default")
            assertThat(cursor.getDouble(2)).isEqualTo(12.5)
            assertThat(cursor.getString(3)).isEqualTo("GRAVEL")
        }
        db.query("SELECT activeBikeProfileId FROM user_settings WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(seededProfileId)
        }
        db.close()
    }
}
