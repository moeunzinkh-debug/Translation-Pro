package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.BuildConfig
import com.example.data.model.AiProvider
import com.example.data.model.TranslationTone
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

        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
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

    // --- Gemini ---
    fun getGeminiApiKey(): String {
        val saved = prefs.getString(KEY_GEMINI_API_KEY, "")
        if (!saved.isNullOrBlank()) return saved
        // Check BuildConfig from .env injection if available
        return try {
            val buildConfigKey = BuildConfig::class.java.getField("GEMINI_API_KEY").get(null) as? String
            if (buildConfigKey != null && !buildConfigKey.isNullFlowKey()) buildConfigKey else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun setGeminiApiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()
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
