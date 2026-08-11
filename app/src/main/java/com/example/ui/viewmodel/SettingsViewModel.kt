package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AiProvider
import com.example.data.model.GeminiKey
import com.example.data.model.GeminiModel
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
    val availableGeminiModels: List<GeminiModel> = emptyList(),
    val isLoadingGeminiModels: Boolean = false,
    val geminiModelsError: String? = null,

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

    // Prevent an older request from replacing results after the active key changes.
    private var modelLoadGeneration = 0

    init {
        loadSettings()
        if (_uiState.value.selectedProvider == AiProvider.GEMINI &&
            _uiState.value.geminiApiKey.isNotBlank()
        ) {
            refreshGeminiModels()
        }
    }

    fun loadSettings() {
        val previous = _uiState.value
        _uiState.value = previous.copy(
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
        if (provider == AiProvider.GEMINI && _uiState.value.availableGeminiModels.isEmpty()) {
            refreshGeminiModels()
        }
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

    fun addGeminiKey(label: String, key: String, limit: Int) {
        settingsRepository.addGeminiKey(label, key, limit)
        loadSettings()
        refreshGeminiModels()
    }

    fun removeGeminiKey(id: String) {
        settingsRepository.removeGeminiKey(id)
        loadSettings()
        if (_uiState.value.geminiApiKey.isBlank()) {
            modelLoadGeneration++
            _uiState.value = _uiState.value.copy(
                availableGeminiModels = emptyList(),
                isLoadingGeminiModels = false,
                geminiModelsError = null
            )
        } else {
            refreshGeminiModels()
        }
    }

    fun selectGeminiKey(id: String) {
        settingsRepository.setActiveGeminiKey(id)
        loadSettings()
        refreshGeminiModels()
    }

    fun onGeminiModelChanged(model: String) {
        val normalizedModel = model.trim().removePrefix("models/")
        settingsRepository.setGeminiModel(normalizedModel)
        _uiState.value = _uiState.value.copy(
            geminiModel = normalizedModel,
            isSavedSuccess = true,
            testConnectionResult = null,
            testConnectionError = null
        )
    }

    /** Fetches every compatible model from Google's live API for the currently active key. */
    fun refreshGeminiModels() {
        val generation = ++modelLoadGeneration
        if (_uiState.value.geminiApiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(
                availableGeminiModels = emptyList(),
                isLoadingGeminiModels = false,
                geminiModelsError = "Add or select a Gemini API key to load all models."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isLoadingGeminiModels = true,
            geminiModelsError = null
        )
        viewModelScope.launch {
            val result = translationRepository.listGeminiModels()
            if (generation != modelLoadGeneration) return@launch
            loadSettings()

            _uiState.value = if (result.isSuccess) {
                _uiState.value.copy(
                    availableGeminiModels = result.getOrDefault(emptyList()),
                    isLoadingGeminiModels = false,
                    geminiModelsError = null
                )
            } else {
                _uiState.value.copy(
                    availableGeminiModels = emptyList(),
                    isLoadingGeminiModels = false,
                    geminiModelsError = result.exceptionOrNull()?.localizedMessage
                        ?: "Could not load Gemini models."
                )
            }
        }
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
            if (provider == AiProvider.GEMINI) loadSettings()
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isTestingConnection = false,
                    testConnectionResult = result.getOrNull() ?: "Success!"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isTestingConnection = false,
                    testConnectionError = result.exceptionOrNull()?.localizedMessage
                        ?: "Connection failed."
                )
            }
        }
    }

    fun dismissSavedMessage() {
        _uiState.value = _uiState.value.copy(isSavedSuccess = false)
    }
}
