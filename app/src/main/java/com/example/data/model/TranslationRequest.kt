package com.example.data.model

data class TranslationRequest(
    val sourceLanguage: String = "Auto-detect",
    val targetLanguage: String = "English",
    val text: String,
    val tone: TranslationTone = TranslationTone.AUTO,
    val isSubtitle: Boolean = false,
    // 0 = normal translation; >= 1 asks the AI for a DIFFERENT, easier-to-understand alternative
    val alternativeAttempt: Int = 0,
    // Translations the user has already seen for the same input; the AI must avoid repeating these
    val previousTranslations: List<String> = emptyList()
)

data class TranslationResult(
    val translatedText: String,
    val detectedSourceLanguage: String? = null,
    val slangNotes: String? = null,
    val providerUsed: AiProvider
)
