package com.fourgeailabs.prismtune.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plex account token, stored encrypted.
 *
 * EncryptedSharedPreferences is tried first; on devices / emulators where the
 * AndroidKeyStore is unavailable it falls back to a private SharedPreferences
 * file so login still works (the token is never logged, and the file is
 * private to this app). Sign-out wipes it.
 */
class TokenStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "prismtune_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.getSharedPreferences("prismtune_secure_fallback", Context.MODE_PRIVATE)
    }

    private val _token = MutableStateFlow(prefs.getString(KEY_TOKEN, null))
    val token: StateFlow<String?> = _token.asStateFlow()

    fun getToken(): String? = _token.value

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
        _token.value = token
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
        _token.value = null
    }

    companion object {
        private const val KEY_TOKEN = "plex_account_token"
    }
}

/** Plain private prefs for non-secret settings (safe-area, quality, etc.). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("prismtune_settings", Context.MODE_PRIVATE)

    var safeAreaFraction: Float
        get() = SafeAreaConfig.clamp(prefs.getFloat(KEY_SAFE_AREA, SafeAreaConfig.DEFAULT_FRACTION))
        set(value) {
            prefs.edit().putFloat(KEY_SAFE_AREA, SafeAreaConfig.clamp(value)).apply()
        }

    /** "Original" (direct play), "320", "192", or "128" kbps transcode ceiling. */
    var audioQuality: String
        get() = prefs.getString(KEY_QUALITY, "Original") ?: "Original"
        set(value) { prefs.edit().putString(KEY_QUALITY, value).apply() }

    var serverId: String?
        get() = prefs.getString(KEY_SERVER, null)
        set(value) { prefs.edit().putString(KEY_SERVER, value).apply() }

    var shuffle: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, false)
        set(value) { prefs.edit().putBoolean(KEY_SHUFFLE, value).apply() }

    /** 0 = off, 1 = all, 2 = one. */
    var repeatMode: Int
        get() = prefs.getInt(KEY_REPEAT, 0)
        set(value) { prefs.edit().putInt(KEY_REPEAT, value).apply() }

    var deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE, null)
            if (id.isNullOrBlank()) {
                id = java.util.UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE, id).apply()
            }
            return id
        }
        set(value) { prefs.edit().putString(KEY_DEVICE, value).apply() }

    companion object {
        private const val KEY_SAFE_AREA = "safe_area_fraction"
        private const val KEY_QUALITY = "audio_quality"
        private const val KEY_SERVER = "server_id"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT = "repeat_mode"
        private const val KEY_DEVICE = "device_id"
    }
}
