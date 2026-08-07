package com.example.data.repository

import com.example.data.api.ChatChoice
import com.example.data.api.ChatCompletionRequest
import com.example.data.api.ChatMessage
import com.example.data.api.GeminiApiService
import com.example.data.api.GeminiContent
import com.example.data.api.GeminiGenerateContentRequest
import com.example.data.api.GeminiGenerationConfig
import com.example.data.api.GeminiPart
import com.example.data.api.OpenAiApiService
import com.example.data.model.AiProvider
import com.example.data.model.TranslationRequest
import com.example.data.model.TranslationResult
import com.example.data.security.SecureSettingsRepository
import com.example.data.service.TranslationService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class TranslationRepository(
    private val settingsRepository: SecureSettingsRepository
) : TranslationService {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val openAiApiService: OpenAiApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.openai.com/v1/") // Default, will override fullUrl dynamically
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenAiApiService::class.java)
    }

    private val geminiApiService: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }

    override suspend fun translate(request: TranslationRequest): Result<TranslationResult> {
        val provider = settingsRepository.getSelectedProvider()
        val apiKey = settingsRepository.getApiKeyForProvider(provider)

        if (apiKey.isBlank()) {
            return Result.failure(
                IllegalArgumentException("API Key for ${provider.displayName} is missing. Please configure it in Settings.")
            )
        }

        // Network call with 1 automatic retry on failure
        var attempts = 0
        var lastException: Throwable? = null

        while (attempts < 2) {
            attempts++
            try {
                val result = when (provider) {
                    AiProvider.SEA_LION -> translateViaSeaLion(request, apiKey)
                    AiProvider.GEMINI -> translateViaGemini(request, apiKey)
                    AiProvider.CHATGPT -> translateViaChatGPT(request, apiKey)
                    AiProvider.CUSTOM -> translateViaCustom(request, apiKey)
                }
                return Result.success(result)
            } catch (e: Exception) {
                lastException = e
                if (attempts < 2 && (e is IOException || isRetryableServerError(e))) {
                    delay(1000) // 1 second backoff before retry
                } else {
                    break
                }
            }
        }

        return Result.failure(lastException ?: Exception("Translation failed after retries."))
    }

    override suspend fun testConnection(provider: AiProvider): Result<String> {
        val apiKey = settingsRepository.getApiKeyForProvider(provider)
        if (apiKey.isBlank()) {
            return Result.failure(IllegalArgumentException("API Key is empty."))
        }

        val testRequest = TranslationRequest(
            sourceLanguage = "English",
            targetLanguage = "Spanish",
            text = "Hello world",
            isSubtitle = true
        )

        return try {
            val result = when (provider) {
                AiProvider.SEA_LION -> translateViaSeaLion(testRequest, apiKey)
                AiProvider.GEMINI -> translateViaGemini(testRequest, apiKey)
                AiProvider.CHATGPT -> translateViaChatGPT(testRequest, apiKey)
                AiProvider.CUSTOM -> translateViaCustom(testRequest, apiKey)
            }
            Result.success("Connection successful! Response: \"${result.translatedText.trim()}\"")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun translateViaSeaLion(
        request: TranslationRequest,
        apiKey: String
    ): TranslationResult {
        val baseUrl = settingsRepository.getSeaLionBaseUrl()
        val model = settingsRepository.getSeaLionModel()
        val endpointUrl = if (baseUrl.endsWith("chat/completions")) baseUrl else "${baseUrl}chat/completions"

        val systemPrompt = buildSystemPrompt(request)
        val userContent = buildUserContent(request)

        val chatRequest = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userContent)
            ),
            temperature = temperatureFor(request)
        )

        val response = openAiApiService.createChatCompletion(
            fullUrl = endpointUrl,
            authorization = "Bearer $apiKey",
            request = chatRequest
        )

        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string() ?: ""
            throw IOException("Sea-Lion API error (${response.code()}): $errBody")
        }

        val rawText = response.body()?.choices?.firstOrNull()?.message?.content
            ?: throw IOException("Empty response from Sea-Lion API")

        return parseTranslationOutput(rawText, request, AiProvider.SEA_LION)
    }

    private suspend fun translateViaChatGPT(
        request: TranslationRequest,
        apiKey: String
    ): TranslationResult {
        val model = settingsRepository.getChatGptModel()
        val endpointUrl = "https://api.openai.com/v1/chat/completions"

        val systemPrompt = buildSystemPrompt(request)
        val userContent = buildUserContent(request)

        val chatRequest = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userContent)
            ),
            temperature = temperatureFor(request)
        )

        val response = openAiApiService.createChatCompletion(
            fullUrl = endpointUrl,
            authorization = "Bearer $apiKey",
            request = chatRequest
        )

        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string() ?: ""
            throw IOException("ChatGPT API error (${response.code()}): $errBody")
        }

        val rawText = response.body()?.choices?.firstOrNull()?.message?.content
            ?: throw IOException("Empty response from ChatGPT API")

        return parseTranslationOutput(rawText, request, AiProvider.CHATGPT)
    }

    private suspend fun translateViaCustom(
        request: TranslationRequest,
        apiKey: String
    ): TranslationResult {
        val baseUrl = settingsRepository.getCustomBaseUrl()
        val model = settingsRepository.getCustomModel()
        val endpointUrl = if (baseUrl.endsWith("chat/completions")) baseUrl else "${baseUrl}chat/completions"

        val systemPrompt = buildSystemPrompt(request)
        val userContent = buildUserContent(request)

        val chatRequest = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userContent)
            ),
            temperature = temperatureFor(request)
        )

        val response = openAiApiService.createChatCompletion(
            fullUrl = endpointUrl,
            authorization = "Bearer $apiKey",
            request = chatRequest
        )

        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string() ?: ""
            throw IOException("Custom API error (${response.code()}): $errBody")
        }

        val rawText = response.body()?.choices?.firstOrNull()?.message?.content
            ?: throw IOException("Empty response from Custom API")

        return parseTranslationOutput(rawText, request, AiProvider.CUSTOM)
    }

    private suspend fun translateViaGemini(
        request: TranslationRequest,
        apiKey: String
    ): TranslationResult {
        val model = settingsRepository.getGeminiModel()
        val systemPrompt = buildSystemPrompt(request)
        val userContent = buildUserContent(request)

        val geminiReq = GeminiGenerateContentRequest(
            contents = listOf(
                GeminiContent(
                    role = "user",
                    parts = listOf(GeminiPart(userContent))
                )
            ),
            systemInstruction = GeminiContent(
                parts = listOf(GeminiPart(systemPrompt))
            ),
            generationConfig = GeminiGenerationConfig(
                temperature = temperatureFor(request)
            )
        )

        val response = geminiApiService.generateContent(
            model = model,
            apiKey = apiKey,
            request = geminiReq
        )

        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string() ?: ""
            throw IOException("Gemini API error (${response.code()}): $errBody")
        }

        val rawText = response.body()?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw IOException("Empty response from Gemini API")

        return parseTranslationOutput(rawText, request, AiProvider.GEMINI)
    }

    private fun buildSystemPrompt(request: TranslationRequest): String {
        if (request.isSubtitle) {
            return """
            You are a professional media subtitle translator.
            Task:
            1. Translate dialogue segments accurately into ${request.targetLanguage}.
            2. Source Language: ${request.sourceLanguage}.
            3. Respect slang, idioms, and cultural context. Translate meaning, not literal words.
            4. Tone instruction: ${request.tone.promptInstruction}.
            5. Keep proper nouns and named entities consistent.
            6. STRICT SUBTITLE CONSTRAINT: Output ONLY the translated dialogue text. Do NOT include line numbers, timestamps, explanatory notes, or intro/outro conversational fluff.
            """.trimIndent()
        }

        val basePrompt = """
        You are a high-precision smart translator specialized in natural idioms and slang.
        Task:
        1. Translate text from ${request.sourceLanguage} to ${request.targetLanguage}.
        2. Understand slang, idioms, metaphors, and cultural context. Translate meaning naturally rather than literal word-for-word.
        3. Tone instruction: ${request.tone.promptInstruction}
        4. Keep proper nouns, places, and brand names consistent.
        5. If slang or idioms are ambiguous or have cultural depth, translate to the most natural equivalent, and optionally append a brief note on a new line at the end starting with '[Note: ...]' explaining the idiom.
        6. OUTPUT FORMAT: Return the translated text directly. Do not add introductory labels like 'Translation:' or 'Here is the translated text:'.
        """.trimIndent()

        // Normal first translation
        if (request.alternativeAttempt <= 0) {
            return basePrompt
        }

        // The user tapped "Translate" again: produce a DIFFERENT, simpler alternative.
        val previousList = request.previousTranslations
            .mapIndexed { index, previous -> "${index + 1}. \"$previous\"" }
            .joinToString("\n")
            .ifBlank { "(none)" }

        return basePrompt + "\n\n" + """
        REPHRASING TASK (alternative #${request.alternativeAttempt}):
        The user has already seen the translation(s) below, but asked again because they want a DIFFERENT version that is EASIER TO UNDERSTAND.
        Previous translation(s) to avoid repeating:
        $previousList
        Requirements for your new translation:
        1. It MUST be clearly different from every previous translation above. Do NOT reuse their distinctive wording, phrases, or sentence structures.
        2. It MUST be simpler and easier to understand: prefer common everyday words, shorter sentences, and the most natural way a native speaker would say it in ${request.targetLanguage}.
        3. Keep the original meaning, tone instruction, and cultural nuance intact.
        """.trimIndent()
    }

    private fun buildUserContent(request: TranslationRequest): String {
        return request.text
    }

    // Low temperature keeps the first translation precise; a higher temperature on
    // re-taps encourages the AI to come up with a genuinely different alternative.
    private fun temperatureFor(request: TranslationRequest): Double {
        return if (request.alternativeAttempt > 0) 0.75 else 0.2
    }

    private fun parseTranslationOutput(
        rawText: String,
        request: TranslationRequest,
        provider: AiProvider
    ): TranslationResult {
        var cleanText = rawText.trim()

        // Extract optional [Note: ...] if present
        var slangNotes: String? = null
        val noteIndex = cleanText.indexOf("[Note:")
        if (noteIndex != -1) {
            slangNotes = cleanText.substring(noteIndex).removePrefix("[Note:").removeSuffix("]").trim()
            cleanText = cleanText.substring(0, noteIndex).trim()
        }

        // Clean any accidental markdown quotes
        if (cleanText.startsWith("\"") && cleanText.endsWith("\"") && cleanText.length > 2) {
            cleanText = cleanText.substring(1, cleanText.length - 1)
        }

        return TranslationResult(
            translatedText = cleanText,
            detectedSourceLanguage = if (request.sourceLanguage == "Auto-detect") "Detected" else request.sourceLanguage,
            slangNotes = slangNotes,
            providerUsed = provider
        )
    }

    private fun isRetryableServerError(e: Exception): Boolean {
        val msg = e.message ?: ""
        return msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504") || msg.contains("429")
    }
}
