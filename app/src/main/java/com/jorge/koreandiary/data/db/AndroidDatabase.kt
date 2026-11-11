package com.jorge.koreandiary.data.db

import android.content.Context
import android.content.res.AssetManager
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jorge.koreandiary.data.standard.StandardCatalog

/**
 * Lo único de la base que depende de Android: dónde vive el archivo. La definición (entidades,
 * DAOs, versión) está en el módulo compartido, igual para escritorio.
 */
fun AppDatabase.Companion.create(context: Context): AppDatabase =
    Room.databaseBuilder<AppDatabase>(context.applicationContext, FILE_NAME)
        // Room 3 exige un driver. El bundled trae su propio SQLite: mismo comportamiento en todo Android.
        .setDriver(BundledSQLiteDriver())
        .build()

/** Catálogo estándar leído de assets/standard. */
fun androidStandardCatalog(assets: AssetManager): StandardCatalog = StandardCatalog { name ->
    assets.open("standard/$name").bufferedReader(Charsets.UTF_8).use { it.readLines() }
}
