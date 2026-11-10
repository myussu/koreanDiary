package com.jorge.koreandiary.data.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Database(
    entities = [
        GrammarPoint::class,
        Word::class,
        MemoryState::class,
        ReviewEvent::class,
        DiaryEntry::class,
        DiaryPage::class,
        Correction::class,
        Sentence::class,
        PendingWord::class,
    ],
    // Al cambiar una entidad (tabla, columna, índice): subir este número y agregar
    // AutoMigration(from = N, to = N + 1) en autoMigrations. Sin eso, la app instalada no abre su base.
    // Borrar o renombrar una columna o tabla necesita además un AutoMigrationSpec.
    version = 1,
    // El esquema de cada versión queda en app/schemas (ver room3 { } en app/build.gradle.kts) y va al repo.
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studyDao(): StudyDao
    abstract fun memoryDao(): MemoryDao
    abstract fun diaryDao(): DiaryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder<AppDatabase>(context.applicationContext, "korean_diary.db")
                // Room 3 exige un driver. El bundled trae su propio SQLite: mismo comportamiento en todo Android.
                .setDriver(BundledSQLiteDriver())
                .build()
    }
}
