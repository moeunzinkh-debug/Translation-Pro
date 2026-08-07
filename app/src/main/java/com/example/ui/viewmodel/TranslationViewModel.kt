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
    val isKeyMissing: Boolean = false,
    // --- "Tap again for an easier alternative" tracking ---
    // How many times the current input has been translated (1 = first translation)
    val translationAttempt: Int = 0,
    // True when the currently shown result is a simplified alternative rather than the first translation
    val isAlternativeResult: Boolean = false,
    // The exact context that produced the currently shown result
    val lastTranslatedInput: String = "",
    val lastUsedSourceLanguage: String = "",
    val lastUsedTargetLanguage: String = "",
    val lastUsedTone: TranslationTone? = null
)

/**
 * True when pressing Translate again will NOT start a fresh translation, but instead
 * rephrase the currently shown answer into a different, easier-to-understand version.
 */
fun TranslationUiState.willRephraseOnTranslate(): Boolean {
    return translatedText.isNotEmpty() &&
        inputText.trim().isNotEmpty() &&
        inputText.trim() == lastTranslatedInput &&
        sourceLanguage == lastUsedSourceLanguage &&
        targetLanguage == lastUsedTargetLanguage &&
        tone == lastUsedTone
}

class TranslationViewModel(
    private val translationRepository: TranslationRepository,
    private val settingsRepository: SecureSettingsRepository
) : ViewModel() {

    // Translations already shown for the current input, sent to the AI so it avoids repeats
    private val shownTranslations = mutableListOf<String>()

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
        shownTranslations.clear()
        _uiState.value = _uiState.value.copy(
            inputText = "",
            translatedText = "",
            slangNotes = null,
            errorMessage = null,
            translationAttempt = 0,
            isAlternativeResult = false,
            lastTranslatedInput = "",
            lastUsedSourceLanguage = "",
            lastUsedTargetLanguage = "",
            lastUsedTone = null
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

        // If the user taps Translate again on the SAME text/languages/tone, they want
        // a different, easier-to-understand version of the answer - not the same one.
        val isRephrase = _uiState.value.willRephraseOnTranslate()
        val attempt = if (isRephrase) _uiState.value.translationAttempt + 1 else 1
        if (!isRephrase) {
            shownTranslations.clear()
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
                isSubtitle = false,
                alternativeAttempt = if (isRephrase) attempt - 1 else 0,
                previousTranslations = shownTranslations.toList()
            )

            val result = translationRepository.translate(req)

            if (result.isSuccess) {
                val data = result.getOrNull()
                val newTranslation = data?.translatedText ?: ""
                if (newTranslation.isNotBlank()) {
                    shownTranslations.add(newTranslation)
                    // Keep the prompt history bounded to the most recent few variants
                    while (shownTranslations.size > 5) shownTranslations.removeAt(0)
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    translatedText = newTranslation,
                    slangNotes = data?.slangNotes,
                    errorMessage = null,
                    translationAttempt = attempt,
                    isAlternativeResult = isRephrase,
                    lastTranslatedInput = text,
                    lastUsedSourceLanguage = _uiState.value.sourceLanguage,
                    lastUsedTargetLanguage = _uiState.value.targetLanguage,
                    lastUsedTone = _uiState.value.tone
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
