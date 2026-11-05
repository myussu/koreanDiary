package com.jorge.koreandiary.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ProviderId {
    const val GEMINI = "gemini"
    const val SHARE = "share"
}

data class AppSettings(
    val profile: String = "",
    val level: String = "3A",
    /** Niveles de gramática a sugerir, separados por coma. */
    val grammarLevels: String = "base,3A",
    val defaultProvider: String = ProviderId.SHARE,
    val geminiModel: String = "gemini-3.5-flash-lite",
    val geminiDailyLimit: Int = 30,
    val stylusOnly: Boolean = true,
) {
    val grammarLevelList get() = grammarLevels.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Preferencias simples en SharedPreferences, expuestas como StateFlow para Compose. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun read() = AppSettings(
        profile = prefs.getString("profile", "") ?: "",
        level = prefs.getString("level", "3A") ?: "3A",
        grammarLevels = prefs.getString("grammarLevels", "base,3A") ?: "base,3A",
        defaultProvider = prefs.getString("defaultProvider", ProviderId.SHARE) ?: ProviderId.SHARE,
        geminiModel = prefs.getString("geminiModel", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite",
        geminiDailyLimit = prefs.getInt("geminiDailyLimit", 30),
        stylusOnly = prefs.getBoolean("stylusOnly", true),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value)
        prefs.edit()
            .putString("profile", s.profile)
            .putString("level", s.level)
            .putString("grammarLevels", s.grammarLevels)
            .putString("defaultProvider", s.defaultProvider)
            .putString("geminiModel", s.geminiModel)
            .putInt("geminiDailyLimit", s.geminiDailyLimit)
            .putBoolean("stylusOnly", s.stylusOnly)
            .apply()
        _state.value = s
    }

    /** Uso diario por proveedor, para no agotar créditos prestados sin darse cuenta. */
    fun usageToday(provider: String): Int {
        val today = LocalDate.now().toString()
        return if (prefs.getString("usage_date_$provider", null) == today) prefs.getInt("usage_count_$provider", 0) else 0
    }

    fun registerUse(provider: String) {
        val today = LocalDate.now().toString()
        prefs.edit()
            .putString("usage_date_$provider", today)
            .putInt("usage_count_$provider", usageToday(provider) + 1)
            .apply()
    }
}

/**
 * Claves de API cifradas con AES-GCM. La clave AES vive en el Android Keystore
 * y nunca sale del hardware seguro; en SharedPreferences solo queda el texto cifrado.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + encrypted
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_SIZE))
            String(cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun has(name: String) = prefs.contains(name)

    companion object {
        const val GEMINI_KEY = "gemini_api_key"
        private const val ALIAS = "korean_diary_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}
