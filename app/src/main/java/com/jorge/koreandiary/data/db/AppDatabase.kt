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
    version = 1,
    // Sin esquema exportado en el MVP. Cuando haya migraciones, activar y configurar schemaDirectory.
    exportSchema = false,
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
