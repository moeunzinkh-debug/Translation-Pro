package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.BuildConfig
import com.example.data.model.AiProvider
import com.example.data.model.GeminiKey
import com.example.data.model.TranslationTone
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SecureSettingsRepository(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "encrypted_app_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to standard SharedPreferences if EncryptedSharedPreferences fails on emulator/test
            context.getSharedPreferences("app_settings_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _selectedProviderFlow = MutableStateFlow(getSelectedProvider())
    val selectedProviderFlow: StateFlow<AiProvider> = _selectedProviderFlow.asStateFlow()

    companion object {
        private const val KEY_SELECTED_PROVIDER = "selected_provider"
        private const val KEY_SEA_LION_API_KEY = "sea_lion_api_key"
        private const val KEY_SEA_LION_BASE_URL = "sea_lion_base_url"
        private const val KEY_SEA_LION_MODEL = "sea_lion_model"

        private const val KEY_GEMINI_API_KEY = "gemini_api_key" // legacy single-key migration
        private const val KEY_GEMINI_KEYS = "gemini_keys_v2"
        private const val KEY_GEMINI_ACTIVE_KEY = "gemini_active_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"

        private const val KEY_CHATGPT_API_KEY = "chatgpt_api_key"
        private const val KEY_CHATGPT_MODEL = "chatgpt_model"

        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
        private const val KEY_CUSTOM_BASE_URL = "custom_base_url"
        private const val KEY_CUSTOM_MODEL = "custom_model"

        private const val KEY_DEFAULT_SOURCE_LANG = "default_source_lang"
        private const val KEY_DEFAULT_TARGET_LANG = "default_target_lang"
        private const val KEY_DEFAULT_TONE = "default_tone"
    }

    fun getSelectedProvider(): AiProvider {
        val id = prefs.getString(KEY_SELECTED_PROVIDER, AiProvider.SEA_LION.id)
        return AiProvider.fromId(id)
    }

    fun setSelectedProvider(provider: AiProvider) {
        prefs.edit().putString(KEY_SELECTED_PROVIDER, provider.id).apply()
        _selectedProviderFlow.value = provider
    }

    // --- Sea-Lion ---
    fun getSeaLionApiKey(): String {
        return prefs.getString(KEY_SEA_LION_API_KEY, "") ?: ""
    }

    fun setSeaLionApiKey(key: String) {
        prefs.edit().putString(KEY_SEA_LION_API_KEY, key.trim()).apply()
    }

    fun getSeaLionBaseUrl(): String {
        return prefs.getString(KEY_SEA_LION_BASE_URL, AiProvider.SEA_LION.defaultBaseUrl) ?: AiProvider.SEA_LION.defaultBaseUrl
    }

    fun setSeaLionBaseUrl(url: String) {
        var formatted = url.trim()
        if (formatted.isNotEmpty() && !formatted.endsWith("/")) {
            formatted += "/"
        }
        prefs.edit().putString(KEY_SEA_LION_BASE_URL, formatted).apply()
    }

    fun getSeaLionModel(): String {
        return prefs.getString(KEY_SEA_LION_MODEL, AiProvider.SEA_LION.defaultModel) ?: AiProvider.SEA_LION.defaultModel
    }

    fun setSeaLionModel(model: String) {
        prefs.edit().putString(KEY_SEA_LION_MODEL, model.trim()).apply()
    }

    // --- Gemini: unlimited local key slots. Gemini does not expose remaining project quota to API keys;
    // the limit below is an optional app-managed daily request budget, shown transparently in the UI.
    fun getGeminiKeys(): List<GeminiKey> {
        val today = LocalDate.now().toString()
        val raw = prefs.getString(KEY_GEMINI_KEYS, "") ?: ""
        val stored = raw.lineSequence().mapNotNull { line ->
            val p = line.split("|", limit = 6)
            if (p.size == 6) GeminiKey(p[0], p[1], p[2], p[3].toIntOrNull() ?: 20, if (p[5] == today) p[4].toIntOrNull() ?: 0 else 0, today) else null
        }.toList()
        if (stored.isNotEmpty()) return stored
        val legacy = prefs.getString(KEY_GEMINI_API_KEY, "").orEmpty()
        return if (legacy.isBlank()) emptyList() else listOf(GeminiKey(UUID.randomUUID().toString(), "Gemini key 1", legacy, 20, 0, today)).also { saveGeminiKeys(it) }
    }

    fun addGeminiKey(label: String, key: String, dailyLimit: Int = 20) {
        if (key.isBlank()) return
        saveGeminiKeys(getGeminiKeys() + GeminiKey(UUID.randomUUID().toString(), label.ifBlank { "Gemini key ${getGeminiKeys().size + 1}" }, key.trim(), dailyLimit.coerceAtLeast(1), 0, LocalDate.now().toString()))
    }
    fun removeGeminiKey(id: String) { saveGeminiKeys(getGeminiKeys().filterNot { it.id == id }); if (prefs.getString(KEY_GEMINI_ACTIVE_KEY, "") == id) prefs.edit().remove(KEY_GEMINI_ACTIVE_KEY).apply() }
    fun setActiveGeminiKey(id: String) { prefs.edit().putString(KEY_GEMINI_ACTIVE_KEY, id).apply() }
    fun recordGeminiRequest() {
        val active = getActiveGeminiKey() ?: return
        saveGeminiKeys(getGeminiKeys().map { if (it.id == active.id) it.copy(usedToday = it.usedToday + 1) else it })
    }
    fun getActiveGeminiKey(): GeminiKey? {
        val keys = getGeminiKeys().filter { it.remainingToday > 0 }
        if (keys.isEmpty()) return getGeminiKeys().firstOrNull()
        val requested = prefs.getString(KEY_GEMINI_ACTIVE_KEY, "")
        return keys.firstOrNull { it.id == requested } ?: keys.first()
    }
    private fun saveGeminiKeys(keys: List<GeminiKey>) {
        prefs.edit().putString(KEY_GEMINI_KEYS, keys.joinToString("\n") { "${it.id}|${it.label.replace("|", " ")}|${it.value}|${it.dailyLimit}|${it.usedToday}|${it.day}" }).apply()
    }
    fun getGeminiApiKey(): String {
        return getActiveGeminiKey()?.value ?: try { (BuildConfig::class.java.getField("GEMINI_API_KEY").get(null) as? String).takeUnless { it.isNullFlowKey() }.orEmpty() } catch (_: Exception) { "" }
    }
    fun setGeminiApiKey(key: String) { // preserves compatibility for old callers
        val existing = getGeminiKeys().firstOrNull()
        if (existing == null) addGeminiKey("Gemini key 1", key) else saveGeminiKeys(getGeminiKeys().map { if (it.id == existing.id) it.copy(value = key.trim()) else it })
    }

    fun getGeminiModel(): String {
        return prefs.getString(KEY_GEMINI_MODEL, AiProvider.GEMINI.defaultModel) ?: AiProvider.GEMINI.defaultModel
    }

    fun setGeminiModel(model: String) {
        prefs.edit().putString(KEY_GEMINI_MODEL, model.trim()).apply()
    }

    // --- ChatGPT ---
    fun getChatGptApiKey(): String {
        return prefs.getString(KEY_CHATGPT_API_KEY, "") ?: ""
    }

    fun setChatGptApiKey(key: String) {
        prefs.edit().putString(KEY_CHATGPT_API_KEY, key.trim()).apply()
    }

    fun getChatGptModel(): String {
        return prefs.getString(KEY_CHATGPT_MODEL, AiProvider.CHATGPT.defaultModel) ?: AiProvider.CHATGPT.defaultModel
    }

    fun setChatGptModel(model: String) {
        prefs.edit().putString(KEY_CHATGPT_MODEL, model.trim()).apply()
    }

    // --- Custom ---
    fun getCustomApiKey(): String {
        return prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
    }

    fun setCustomApiKey(key: String) {
        prefs.edit().putString(KEY_CUSTOM_API_KEY, key.trim()).apply()
    }

    fun getCustomBaseUrl(): String {
        return prefs.getString(KEY_CUSTOM_BASE_URL, AiProvider.CUSTOM.defaultBaseUrl) ?: AiProvider.CUSTOM.defaultBaseUrl
    }

    fun setCustomBaseUrl(url: String) {
        var formatted = url.trim()
        if (formatted.isNotEmpty() && !formatted.endsWith("/")) {
            formatted += "/"
        }
        prefs.edit().putString(KEY_CUSTOM_BASE_URL, formatted).apply()
    }

    fun getCustomModel(): String {
        return prefs.getString(KEY_CUSTOM_MODEL, AiProvider.CUSTOM.defaultModel) ?: AiProvider.CUSTOM.defaultModel
    }

    fun setCustomModel(model: String) {
        prefs.edit().putString(KEY_CUSTOM_MODEL, model.trim()).apply()
    }

    // --- Preferences ---
    fun getDefaultSourceLanguage(): String {
        return prefs.getString(KEY_DEFAULT_SOURCE_LANG, "Auto-detect") ?: "Auto-detect"
    }

    fun setDefaultSourceLanguage(lang: String) {
        prefs.edit().putString(KEY_DEFAULT_SOURCE_LANG, lang).apply()
    }

    fun getDefaultTargetLanguage(): String {
        return prefs.getString(KEY_DEFAULT_TARGET_LANG, "English") ?: "English"
    }

    fun setDefaultTargetLanguage(lang: String) {
        prefs.edit().putString(KEY_DEFAULT_TARGET_LANG, lang).apply()
    }

    fun getDefaultTone(): TranslationTone {
        val name = prefs.getString(KEY_DEFAULT_TONE, TranslationTone.AUTO.name)
        return try {
            TranslationTone.valueOf(name ?: TranslationTone.AUTO.name)
        } catch (e: Exception) {
            TranslationTone.AUTO
        }
    }

    fun setDefaultTone(tone: TranslationTone) {
        prefs.edit().putString(KEY_DEFAULT_TONE, tone.name).apply()
    }

    fun getApiKeyForProvider(provider: AiProvider): String {
        return when (provider) {
            AiProvider.SEA_LION -> getSeaLionApiKey()
            AiProvider.GEMINI -> getGeminiApiKey()
            AiProvider.CHATGPT -> getChatGptApiKey()
            AiProvider.CUSTOM -> getCustomApiKey()
        }
    }

    private fun String?.isNullFlowKey(): Boolean {
        return this.isNullOrBlank() || this == "MY_GEMINI_API_KEY" || this.contains("YOUR_")
    }
}
