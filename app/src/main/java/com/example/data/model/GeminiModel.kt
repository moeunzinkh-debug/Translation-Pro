package com.example.data.model

/** A Gemini model returned by Google's live Models API. */
data class GeminiModel(
    val id: String,
    val displayName: String,
    val description: String = "",
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null,
    val supportedGenerationMethods: List<String> = emptyList()
) {
    /** Gemini 2.x models can be blocked for newly-created API projects. */
    val isLegacyForNewUsers: Boolean
        get() = id.startsWith("gemini-2.")
}
