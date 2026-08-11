package com.example.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// Interactions API DTOs. This is Google's recommended API for current Gemini models.
@JsonClass(generateAdapter = true)
data class GeminiInteractionRequest(
    @Json(name = "model") val model: String,
    @Json(name = "input") val input: List<GeminiInteractionContent>,
    @Json(name = "system_instruction") val systemInstruction: String? = null,
    @Json(name = "generation_config") val generationConfig: GeminiInteractionGenerationConfig? = null,
    @Json(name = "transcription_config") val transcriptionConfig: GeminiTranscriptionConfig? = null,
    @Json(name = "store") val store: Boolean = false
)

@JsonClass(generateAdapter = true)
data class GeminiTranscriptionConfig(
    @Json(name = "diarization_mode") val diarizationMode: String? = null,
    @Json(name = "language_codes") val languageCodes: List<String>? = null,
    @Json(name = "timestamp_granularities") val timestampGranularities: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiInteractionGenerationConfig(
    @Json(name = "temperature") val temperature: Double? = null
)

@JsonClass(generateAdapter = true)
data class GeminiInteractionContent(
    @Json(name = "type") val type: String,
    @Json(name = "text") val text: String? = null,
    @Json(name = "mime_type") val mimeType: String? = null,
    @Json(name = "data") val data: String? = null,
    // Present on word_info content blocks when transcription diarization is enabled.
    @Json(name = "speaker") val speaker: String? = null,
    @Json(name = "start_offset") val startOffset: String? = null,
    @Json(name = "end_offset") val endOffset: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiInteractionResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "steps") val steps: List<GeminiInteractionStep> = emptyList()
)

@JsonClass(generateAdapter = true)
data class GeminiInteractionStep(
    @Json(name = "type") val type: String? = null,
    @Json(name = "content") val content: List<GeminiInteractionContent> = emptyList()
)

// Models API DTOs. The API is paginated, so callers must follow nextPageToken.
@JsonClass(generateAdapter = true)
data class GeminiListModelsResponse(
    @Json(name = "models") val models: List<GeminiApiModel> = emptyList(),
    @Json(name = "nextPageToken") val nextPageToken: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiApiModel(
    @Json(name = "name") val name: String,
    @Json(name = "displayName") val displayName: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "inputTokenLimit") val inputTokenLimit: Int? = null,
    @Json(name = "outputTokenLimit") val outputTokenLimit: Int? = null,
    @Json(name = "supportedGenerationMethods") val supportedGenerationMethods: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class GeminiErrorEnvelope(
    @Json(name = "error") val error: GeminiErrorBody? = null
)

@JsonClass(generateAdapter = true)
data class GeminiErrorBody(
    @Json(name = "code") val code: Int? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "status") val status: String? = null
)
