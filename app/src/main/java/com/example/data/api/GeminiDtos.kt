package com.example.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// Interactions API DTOs. This is Google's recommended API for current Gemini models.
//
// ── Instant mode notes ────────────────────────────────────────────────────────
// `thinking_level` is by far the biggest latency lever. Gemini 3.x models are hybrid
// reasoning models: they emit thought tokens BEFORE any visible answer, and when
// `thinking_level` is omitted the API defaults to a HIGH effort level. That is why even
// translating the single word "Hi" used to feel as slow as a full paragraph — the cost is
// per-request overhead, not per-word. We always pin the level (see
// TranslationRepository.fastestThinkingLevel) and disable thought summaries.
//
// `temperature` is intentionally GONE: Gemini 3.6+ ignores sampling parameters entirely
// ("custom values for sampling parameters are ignored if set"). `seed` is used instead as
// the only supported diversity lever.
@JsonClass(generateAdapter = true)
data class GeminiInteractionRequest(
    @Json(name = "model") val model: String,
    @Json(name = "input") val input: List<GeminiInteractionContent>,
    @Json(name = "system_instruction") val systemInstruction: String? = null,
    @Json(name = "generation_config") val generationConfig: GeminiInteractionGenerationConfig? = null,
    @Json(name = "store") val store: Boolean = false,
    // When true the endpoint replies with text/event-stream so the UI can paint tokens as
    // they arrive instead of staring at a spinner for the whole response.
    @Json(name = "stream") val stream: Boolean = false
)

@JsonClass(generateAdapter = true)
data class GeminiInteractionGenerationConfig(
    // "minimal" | "low" | "medium" | "high". "minimal" is rejected by Gemini 3.7/3.8 Flash.
    @Json(name = "thinking_level") val thinkingLevel: String? = null,
    // "none" | "auto". "none" stops the API from generating thought summaries we would discard.
    @Json(name = "thinking_summaries") val thinkingSummaries: String? = null,
    // Generous headroom so long input is never truncated, but still bounded against runaway output.
    @Json(name = "max_output_tokens") val maxOutputTokens: Int? = null,
    // Best-effort reproducibility. Varied per re-tap to compensate for temperature being ignored.
    @Json(name = "seed") val seed: Int? = null
)

@JsonClass(generateAdapter = true)
data class GeminiInteractionContent(
    @Json(name = "type") val type: String,
    @Json(name = "text") val text: String? = null,
    @Json(name = "mime_type") val mimeType: String? = null,
    @Json(name = "data") val data: String? = null
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
