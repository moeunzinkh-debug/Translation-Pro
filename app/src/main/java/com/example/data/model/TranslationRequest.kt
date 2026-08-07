package com.example.data.model

data class TranslationRequest(
    val sourceLanguage: String = "Auto-detect",
    val targetLanguage: String = "English",
    val text: String,
    val tone: TranslationTone = TranslationTone.AUTO,
    val isSubtitle: Boolean = false
)

data class TranslationResult(
    val translatedText: String,
    val detectedSourceLanguage: String? = null,
    val slangNotes: String? = null,
    val providerUsed: AiProvider
)
