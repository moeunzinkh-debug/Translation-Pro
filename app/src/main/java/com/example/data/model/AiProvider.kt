package com.example.data.model

enum class AiProvider(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val description: String
) {
    SEA_LION(
        id = "sea_lion",
        displayName = "Sea-Lion AI",
        defaultBaseUrl = "https://api.sea-lion.ai/v1/",
        defaultModel = "aisingapore/sea-lion-7b-instruct",
        description = "SEA-LION regional LLM fine-tuned for Southeast Asian languages & cultural nuance."
    ),
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/",
        defaultModel = "gemini-3.6-flash",
        description = "Google's high-speed multimodal AI model with strong multilingual translation."
    ),
    CHATGPT(
        id = "chatgpt",
        displayName = "OpenAI ChatGPT",
        defaultBaseUrl = "https://api.openai.com/v1/",
        defaultModel = "gpt-4o-mini",
        description = "OpenAI GPT model for conversational & idiomatic language translation."
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom Endpoint",
        defaultBaseUrl = "https://api.example.com/v1/",
        defaultModel = "custom-model",
        description = "Use your own custom OpenAI-compatible REST API endpoint."
    );

    companion object {
        fun fromId(id: String?): AiProvider {
            return entries.find { it.id == id } ?: SEA_LION
        }
    }
}

enum class TranslationTone(val displayName: String, val promptInstruction: String) {
    AUTO("Natural / Auto", "Match the exact tone (formal or casual) of the original text."),
    FORMAL("Formal / Polite", "Use formal, polite, and respectful language standard for business or official contexts."),
    CASUAL("Casual / Conversational", "Use informal, relaxed, conversational language standard among close friends.");
}
