package com.jorge.koreandiary.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

/**
 * La base es la misma en Android y en escritorio. Cada plataforma solo decide dónde vive el
 * archivo y la abre con `Room.databaseBuilder` (Android: `create(context)` en la app; escritorio:
 * `DesktopDatabase`). Siempre con `BundledSQLiteDriver`: el mismo SQLite en todas partes.
 */
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
    // Al cambiar una entidad (tabla, columna, índice): subir VERSION y agregar
    // AutoMigration(from = N, to = N + 1) en autoMigrations. Sin eso, la app instalada no abre su base.
    // Borrar o renombrar una columna o tabla necesita además un AutoMigrationSpec.
    version = AppDatabase.VERSION,
    // El esquema de cada versión queda en shared/schemas (ver room3 { } en shared/build.gradle.kts) y va al repo.
    exportSchema = true,
)
// Room genera el constructor de cada plataforma: en escritorio no hay reflexión que lo encuentre sola.
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studyDao(): StudyDao
    abstract fun memoryDao(): MemoryDao
    abstract fun diaryDao(): DiaryDao

    companion object {
        /** Versión del esquema (`PRAGMA user_version`). Una copia más nueva no se puede importar. */
        const val VERSION = 1

        /** Archivo de la base. La copia de seguridad (`backup/`) lo copia y lo reemplaza con este nombre. */
        const val FILE_NAME = "korean_diary.db"
    }
}

// El compilador de Room genera los `actual` de cada plataforma.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
