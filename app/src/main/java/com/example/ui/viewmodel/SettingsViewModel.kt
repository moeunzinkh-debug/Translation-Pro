package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AiProvider
import com.example.data.model.GeminiKey
import com.example.data.repository.TranslationRepository
import com.example.data.security.SecureSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val selectedProvider: AiProvider = AiProvider.SEA_LION,

    // Sea-Lion
    val seaLionApiKey: String = "",
    val seaLionBaseUrl: String = "",
    val seaLionModel: String = "",

    // Gemini
    val geminiApiKey: String = "",
    val geminiModel: String = "",
    val geminiKeys: List<GeminiKey> = emptyList(),

    // ChatGPT
    val chatGptApiKey: String = "",
    val chatGptModel: String = "",

    // Custom
    val customApiKey: String = "",
    val customBaseUrl: String = "",
    val customModel: String = "",

    val isTestingConnection: Boolean = false,
    val testConnectionResult: String? = null,
    val testConnectionError: String? = null,
    val isSavedSuccess: Boolean = false
)

class SettingsViewModel(
    private val settingsRepository: SecureSettingsRepository,
    private val translationRepository: TranslationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    fun loadSettings() {
        _uiState.value = SettingsUiState(
            selectedProvider = settingsRepository.getSelectedProvider(),

            seaLionApiKey = settingsRepository.getSeaLionApiKey(),
            seaLionBaseUrl = settingsRepository.getSeaLionBaseUrl(),
            seaLionModel = settingsRepository.getSeaLionModel(),

            geminiApiKey = settingsRepository.getGeminiApiKey(),
            geminiModel = settingsRepository.getGeminiModel(),
            geminiKeys = settingsRepository.getGeminiKeys(),

            chatGptApiKey = settingsRepository.getChatGptApiKey(),
            chatGptModel = settingsRepository.getChatGptModel(),

            customApiKey = settingsRepository.getCustomApiKey(),
            customBaseUrl = settingsRepository.getCustomBaseUrl(),
            customModel = settingsRepository.getCustomModel()
        )
    }

    fun onSelectProvider(provider: AiProvider) {
        settingsRepository.setSelectedProvider(provider)
        _uiState.value = _uiState.value.copy(
            selectedProvider = provider,
            testConnectionResult = null,
            testConnectionError = null
        )
    }

    // --- Sea Lion setters ---
    fun onSeaLionApiKeyChanged(key: String) {
        settingsRepository.setSeaLionApiKey(key)
        _uiState.value = _uiState.value.copy(seaLionApiKey = key, isSavedSuccess = true)
    }

    fun onSeaLionBaseUrlChanged(url: String) {
        settingsRepository.setSeaLionBaseUrl(url)
        _uiState.value = _uiState.value.copy(seaLionBaseUrl = url, isSavedSuccess = true)
    }

    fun onSeaLionModelChanged(model: String) {
        settingsRepository.setSeaLionModel(model)
        _uiState.value = _uiState.value.copy(seaLionModel = model, isSavedSuccess = true)
    }

    // --- Gemini setters ---
    fun onGeminiApiKeyChanged(key: String) {
        settingsRepository.setGeminiApiKey(key)
        _uiState.value = _uiState.value.copy(geminiApiKey = key, isSavedSuccess = true)
    }

    fun addGeminiKey(label: String, key: String, limit: Int) { settingsRepository.addGeminiKey(label, key, limit); loadSettings() }
    fun removeGeminiKey(id: String) { settingsRepository.removeGeminiKey(id); loadSettings() }
    fun selectGeminiKey(id: String) { settingsRepository.setActiveGeminiKey(id); loadSettings() }

    fun onGeminiModelChanged(model: String) {
        settingsRepository.setGeminiModel(model)
        _uiState.value = _uiState.value.copy(geminiModel = model, isSavedSuccess = true)
    }

    // --- ChatGPT setters ---
    fun onChatGptApiKeyChanged(key: String) {
        settingsRepository.setChatGptApiKey(key)
        _uiState.value = _uiState.value.copy(chatGptApiKey = key, isSavedSuccess = true)
    }

    fun onChatGptModelChanged(model: String) {
        settingsRepository.setChatGptModel(model)
        _uiState.value = _uiState.value.copy(chatGptModel = model, isSavedSuccess = true)
    }

    // --- Custom setters ---
    fun onCustomApiKeyChanged(key: String) {
        settingsRepository.setCustomApiKey(key)
        _uiState.value = _uiState.value.copy(customApiKey = key, isSavedSuccess = true)
    }

    fun onCustomBaseUrlChanged(url: String) {
        settingsRepository.setCustomBaseUrl(url)
        _uiState.value = _uiState.value.copy(customBaseUrl = url, isSavedSuccess = true)
    }

    fun onCustomModelChanged(model: String) {
        settingsRepository.setCustomModel(model)
        _uiState.value = _uiState.value.copy(customModel = model, isSavedSuccess = true)
    }

    fun testConnection() {
        val provider = _uiState.value.selectedProvider
        _uiState.value = _uiState.value.copy(
            isTestingConnection = true,
            testConnectionResult = null,
            testConnectionError = null
        )

        viewModelScope.launch {
            val result = translationRepository.testConnection(provider)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isTestingConnection = false,
                    testConnectionResult = result.getOrNull() ?: "Success!"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isTestingConnection = false,
                    testConnectionError = result.exceptionOrNull()?.localizedMessage ?: "Connection failed."
                )
            }
        }
    }

    fun dismissSavedMessage() {
        _uiState.value = _uiState.value.copy(isSavedSuccess = false)
    }
}
