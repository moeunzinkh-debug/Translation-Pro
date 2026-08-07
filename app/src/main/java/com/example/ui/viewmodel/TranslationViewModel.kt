package com.example.ui.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AiProvider
import com.example.data.model.TranslationRequest
import com.example.data.model.TranslationResult
import com.example.data.model.TranslationTone
import com.example.data.repository.TranslationRepository
import com.example.data.security.SecureSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TranslationUiState(
    val inputText: String = "",
    val translatedText: String = "",
    val sourceLanguage: String = "Auto-detect",
    val targetLanguage: String = "English",
    val tone: TranslationTone = TranslationTone.AUTO,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val slangNotes: String? = null,
    val activeProvider: AiProvider = AiProvider.SEA_LION,
    val isKeyMissing: Boolean = false
)

class TranslationViewModel(
    private val translationRepository: TranslationRepository,
    private val settingsRepository: SecureSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        TranslationUiState(
            sourceLanguage = settingsRepository.getDefaultSourceLanguage(),
            targetLanguage = settingsRepository.getDefaultTargetLanguage(),
            tone = settingsRepository.getDefaultTone(),
            activeProvider = settingsRepository.getSelectedProvider(),
            isKeyMissing = settingsRepository.getApiKeyForProvider(settingsRepository.getSelectedProvider()).isBlank()
        )
    )
    val uiState: StateFlow<TranslationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.selectedProviderFlow.collect { provider ->
                val apiKey = settingsRepository.getApiKeyForProvider(provider)
                _uiState.value = _uiState.value.copy(
                    activeProvider = provider,
                    isKeyMissing = apiKey.isBlank()
                )
            }
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text, errorMessage = null)
    }

    fun onSourceLanguageSelected(lang: String) {
        _uiState.value = _uiState.value.copy(sourceLanguage = lang)
        settingsRepository.setDefaultSourceLanguage(lang)
    }

    fun onTargetLanguageSelected(lang: String) {
        _uiState.value = _uiState.value.copy(targetLanguage = lang)
        settingsRepository.setDefaultTargetLanguage(lang)
    }

    fun onToneSelected(tone: TranslationTone) {
        _uiState.value = _uiState.value.copy(tone = tone)
        settingsRepository.setDefaultTone(tone)
    }

    fun swapLanguages() {
        val currentSource = _uiState.value.sourceLanguage
        val currentTarget = _uiState.value.targetLanguage
        if (currentSource != "Auto-detect") {
            _uiState.value = _uiState.value.copy(
                sourceLanguage = currentTarget,
                targetLanguage = currentSource
            )
        }
    }

    fun clearInput() {
        _uiState.value = _uiState.value.copy(
            inputText = "",
            translatedText = "",
            slangNotes = null,
            errorMessage = null
        )
    }

    fun translate() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please enter text to translate.")
            return
        }

        val provider = settingsRepository.getSelectedProvider()
        val apiKey = settingsRepository.getApiKeyForProvider(provider)
        if (apiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "API Key for ${provider.displayName} is missing. Configure it in Settings.",
                isKeyMissing = true
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isLoading = true,
            errorMessage = null,
            slangNotes = null,
            isKeyMissing = false
        )

        viewModelScope.launch {
            val req = TranslationRequest(
                sourceLanguage = _uiState.value.sourceLanguage,
                targetLanguage = _uiState.value.targetLanguage,
                text = text,
                tone = _uiState.value.tone,
                isSubtitle = false
            )

            val result = translationRepository.translate(req)

            if (result.isSuccess) {
                val data = result.getOrNull()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    translatedText = data?.translatedText ?: "",
                    slangNotes = data?.slangNotes,
                    errorMessage = null
                )
            } else {
                val err = result.exceptionOrNull()?.message ?: "Translation failed."
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = err
                )
            }
        }
    }

    fun copyToClipboard(context: Context, text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Translation", text)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            false
        }
    }
}
