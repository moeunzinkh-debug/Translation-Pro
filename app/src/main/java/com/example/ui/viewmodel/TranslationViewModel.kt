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
import kotlinx.coroutines.CancellationException
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

    // Translations already shown for the current input, sent to the AI so it avoids repeats.
    private val shownTranslations = mutableListOf<String>()
    // Prevent a late response for an older input/language selection from replacing the current UI.
    private var translationGeneration = 0

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
                val providerChanged = _uiState.value.activeProvider != provider
                if (providerChanged) {
                    translationGeneration++
                    shownTranslations.clear()
                }
                _uiState.value = _uiState.value.copy(
                    activeProvider = provider,
                    isKeyMissing = apiKey.isBlank(),
                    isLoading = if (providerChanged) false else _uiState.value.isLoading,
                    translatedText = if (providerChanged) "" else _uiState.value.translatedText,
                    slangNotes = if (providerChanged) null else _uiState.value.slangNotes,
                    errorMessage = if (providerChanged) null else _uiState.value.errorMessage,
                    translationAttempt = if (providerChanged) 0 else _uiState.value.translationAttempt,
                    isAlternativeResult = if (providerChanged) false else _uiState.value.isAlternativeResult,
                    lastTranslatedInput = if (providerChanged) "" else _uiState.value.lastTranslatedInput,
                    lastUsedSourceLanguage = if (providerChanged) "" else _uiState.value.lastUsedSourceLanguage,
                    lastUsedTargetLanguage = if (providerChanged) "" else _uiState.value.lastUsedTargetLanguage,
                    lastUsedTone = if (providerChanged) null else _uiState.value.lastUsedTone
                )
            }
        }
    }

    fun refreshProviderStatus() {
        val provider = settingsRepository.getSelectedProvider()
        val apiKey = settingsRepository.getApiKeyForProvider(provider)
        _uiState.value = _uiState.value.copy(
            activeProvider = provider,
            isKeyMissing = apiKey.isBlank()
        )
    }

    fun onInputTextChanged(text: String) {
        if (text == _uiState.value.inputText) return
        clearCurrentResult()
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun onSourceLanguageSelected(lang: String) {
        clearCurrentResult()
        _uiState.value = _uiState.value.copy(sourceLanguage = lang)
        settingsRepository.setDefaultSourceLanguage(lang)
    }

    fun onTargetLanguageSelected(lang: String) {
        clearCurrentResult()
        _uiState.value = _uiState.value.copy(targetLanguage = lang)
        settingsRepository.setDefaultTargetLanguage(lang)
    }

    fun onToneSelected(tone: TranslationTone) {
        clearCurrentResult()
        _uiState.value = _uiState.value.copy(tone = tone)
        settingsRepository.setDefaultTone(tone)
    }

    fun swapLanguages() {
        val currentSource = _uiState.value.sourceLanguage
        val currentTarget = _uiState.value.targetLanguage
        if (currentSource != "Auto-detect") {
            clearCurrentResult()
            _uiState.value = _uiState.value.copy(
                sourceLanguage = currentTarget,
                targetLanguage = currentSource
            )
            settingsRepository.setDefaultSourceLanguage(currentTarget)
            settingsRepository.setDefaultTargetLanguage(currentSource)
        }
    }

    private fun clearCurrentResult() {
        translationGeneration++
        shownTranslations.clear()
        _uiState.value = _uiState.value.copy(
            translatedText = "",
            slangNotes = null,
            errorMessage = null,
            isLoading = false,
            translationAttempt = 0,
            isAlternativeResult = false,
            lastTranslatedInput = "",
            lastUsedSourceLanguage = "",
            lastUsedTargetLanguage = "",
            lastUsedTone = null
        )
    }

    fun clearInput() {
        translationGeneration++
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
        val currentState = _uiState.value
        if (currentState.isLoading) return

        val text = currentState.inputText.trim()
        if (text.isEmpty()) {
            _uiState.value = currentState.copy(errorMessage = "Please enter text to translate.")
            return
        }

        val provider = settingsRepository.getSelectedProvider()
        val apiKey = settingsRepository.getApiKeyForProvider(provider)
        if (apiKey.isBlank()) {
            val message = if (provider == AiProvider.GEMINI && settingsRepository.getGeminiKeys().isNotEmpty()) {
                "All Gemini keys have reached their app-managed daily request budget. Add another key or wait until tomorrow."
            } else {
                "API Key for ${provider.displayName} is missing. Configure it in Settings."
            }
            _uiState.value = currentState.copy(
                errorMessage = message,
                isKeyMissing = true
            )
            return
        }

        // Capture the complete request context before launching. The user can edit the input or
        // change languages while the network request is running; that must not alter this request.
        val sourceLanguage = currentState.sourceLanguage
        val targetLanguage = currentState.targetLanguage
        val tone = currentState.tone
        val isRephrase = currentState.willRephraseOnTranslate()
        val attempt = if (isRephrase) currentState.translationAttempt + 1 else 1
        if (!isRephrase) shownTranslations.clear()
        val previousTranslations = shownTranslations.toList()
        val requestGeneration = ++translationGeneration

        _uiState.value = currentState.copy(
            isLoading = true,
            errorMessage = null,
            slangNotes = null,
            isKeyMissing = false
        )

        viewModelScope.launch {
            val result = try {
                translationRepository.translate(
                    TranslationRequest(
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        text = text,
                        tone = tone,
                        isSubtitle = false,
                        alternativeAttempt = if (isRephrase) attempt - 1 else 0,
                        previousTranslations = previousTranslations
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure<TranslationResult>(e)
            }

            // A newer input or language selection has invalidated this response.
            if (requestGeneration != translationGeneration) return@launch

            if (result.isSuccess) {
                val data = result.getOrNull()
                val newTranslation = data?.translatedText.orEmpty().trim()
                if (newTranslation.isBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Translation provider returned an empty response."
                    )
                    return@launch
                }

                shownTranslations.add(newTranslation)
                while (shownTranslations.size > 5) shownTranslations.removeAt(0)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    translatedText = newTranslation,
                    slangNotes = data?.slangNotes,
                    errorMessage = null,
                    translationAttempt = attempt,
                    isAlternativeResult = isRephrase,
                    lastTranslatedInput = text,
                    lastUsedSourceLanguage = sourceLanguage,
                    lastUsedTargetLanguage = targetLanguage,
                    lastUsedTone = tone
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
