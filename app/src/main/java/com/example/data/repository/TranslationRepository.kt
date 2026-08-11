package com.example.data.repository

import android.util.Base64
import com.example.data.api.ChatCompletionRequest
import com.example.data.api.ChatMessage
import com.example.data.api.GeminiApiService
import com.example.data.api.GeminiErrorEnvelope
import com.example.data.api.GeminiInteractionContent
import com.example.data.api.GeminiInteractionGenerationConfig
import com.example.data.api.GeminiInteractionRequest
import com.example.data.api.OpenAiApiService
import com.example.data.model.AiProvider
import com.example.data.model.GeminiModel
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
            isSubtitle = false
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
        val model = normalizedGeminiModel(settingsRepository.getGeminiModel())
        val interactionRequest = GeminiInteractionRequest(
            model = model,
            input = listOf(
                GeminiInteractionContent(type = "text", text = buildUserContent(request))
            ),
            systemInstruction = buildSystemPrompt(request),
            generationConfig = GeminiInteractionGenerationConfig(
                temperature = temperatureFor(request)
            ),
            // Translation requests do not need server-side conversation storage.
            store = false
        )

        val response = geminiApiService.createInteraction(
            apiKey = apiKey,
            request = interactionRequest
        )

        if (!response.isSuccessful) {
            throw geminiApiException(
                code = response.code(),
                errorBody = response.errorBody()?.string().orEmpty(),
                model = model
            )
        }

        val rawText = response.body()?.steps
            .orEmpty()
            .asSequence()
            .filter { it.type == "model_output" }
            .flatMap { it.content.asSequence() }
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString(separator = "")
            .takeIf { it.isNotBlank() }
            ?: throw IOException("Gemini returned an empty translation.")

        settingsRepository.recordGeminiRequest()
        return parseTranslationOutput(rawText, request, AiProvider.GEMINI)
    }

    /**
     * Loads every model page from Google's live Models API and returns all models that advertise
     * generateContent support. This avoids a hard-coded list becoming stale when Google changes
     * model availability.
     */
    suspend fun listGeminiModels(): Result<List<GeminiModel>> = try {
        val apiKey = settingsRepository.getGeminiApiKey()
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("Add or select a Gemini API key before loading models.")
        }

        val allModels = mutableListOf<GeminiModel>()
        val seenPageTokens = mutableSetOf<String>()
        var pageToken: String? = null

        do {
            val response = geminiApiService.listModels(
                apiKey = apiKey,
                pageSize = 1000,
                pageToken = pageToken
            )
            if (!response.isSuccessful) {
                throw geminiApiException(
                    code = response.code(),
                    errorBody = response.errorBody()?.string().orEmpty()
                )
            }

            val body = response.body()
                ?: throw IOException("Gemini returned an empty model list.")

            allModels += body.models
                .filter { model ->
                    model.supportedGenerationMethods.any {
                        it.equals("generateContent", ignoreCase = true)
                    }
                }
                .map { model ->
                    val id = normalizedGeminiModel(model.name)
                    GeminiModel(
                        id = id,
                        displayName = model.displayName?.takeIf { it.isNotBlank() } ?: id,
                        description = model.description.orEmpty(),
                        inputTokenLimit = model.inputTokenLimit,
                        outputTokenLimit = model.outputTokenLimit,
                        supportedGenerationMethods = model.supportedGenerationMethods
                    )
                }

            val nextToken = body.nextPageToken?.takeIf { it.isNotBlank() }
            pageToken = if (nextToken != null && seenPageTokens.add(nextToken)) nextToken else null
        } while (pageToken != null)

        val preferredOrder = listOf(
            "gemini-3.6-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.1-flash-lite"
        )
        val models = allModels
            .distinctBy { it.id }
            .sortedWith(
                compareBy<GeminiModel> {
                    preferredOrder.indexOf(it.id).let { index ->
                        if (index == -1) Int.MAX_VALUE else index
                    }
                }.thenBy { it.displayName.lowercase() }
                    .thenBy { it.id }
            )

        if (models.isEmpty()) {
            throw IOException("No Gemini text-generation models are available for this API key.")
        }
        Result.success(models)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Sends an audio clip to Gemini and returns a speaker-labelled transcript. */
    suspend fun transcribe(audio: ByteArray, mimeType: String, languageHint: String): Result<String> = try {
        val apiKey = settingsRepository.getGeminiApiKey()
        if (apiKey.isBlank()) throw IllegalArgumentException("Add a Gemini API key in Settings first.")

        val model = normalizedGeminiModel(settingsRepository.getGeminiModel())
        val speakerInstruction = """
            You are an accurate audio transcription and speaker-diarization assistant.
            Transcribe the spoken words exactly and separate the transcript by voice.
            Use stable labels such as Speaker 1, Speaker 2, Speaker 3 for distinct voices.
            Start a new chunk whenever the active speaker changes, even if the change happens
            in the middle of a sentence. Keep consecutive speech from the same speaker in one
            chunk. If a speaker returns later, reuse the same speaker number.
            Do not guess names or identities. Do not merge two different speakers into one chunk.
            Return only the transcript in this exact format, with a blank line between chunks:
            [Speaker 1]
            spoken words from the first voice

            [Speaker 2]
            spoken words from the second voice
            Do not include timestamps, explanations, sound descriptions, or a summary.
        """.trimIndent()
        val prompt = "Transcribe this audio accurately${if (languageHint.isBlank()) "" else " in $languageHint"}. Apply the speaker-chunk format from the instructions."
        val request = GeminiInteractionRequest(
            model = model,
            input = listOf(
                GeminiInteractionContent(
                    type = "audio",
                    mimeType = mimeType,
                    data = Base64.encodeToString(audio, Base64.NO_WRAP)
                ),
                GeminiInteractionContent(type = "text", text = prompt)
            ),
            systemInstruction = speakerInstruction,
            generationConfig = GeminiInteractionGenerationConfig(temperature = 0.1),
            store = false
        )

        val response = geminiApiService.createInteraction(apiKey, request)
        if (!response.isSuccessful) {
            throw geminiApiException(
                code = response.code(),
                errorBody = response.errorBody()?.string().orEmpty(),
                model = model
            )
        }

        val text = response.body()?.steps
            .orEmpty()
            .asSequence()
            .filter { it.type == "model_output" }
            .flatMap { it.content.asSequence() }
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString(separator = "")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: throw IOException("Gemini returned an empty transcript.")

        settingsRepository.recordGeminiRequest()
        Result.success(text)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun buildSystemPrompt(request: TranslationRequest): String {
        if (request.isSubtitle) {
            return """
            You are a professional media subtitle translator.
            Task:
            1. Translate every dialogue segment accurately into ${request.targetLanguage}.
            2. Source language: ${request.sourceLanguage}.
            3. Target language (must use): ${request.targetLanguage}. Do not return the source language unchanged unless a word is a proper noun or has no natural translation.
            4. Respect slang, idioms, and cultural context. Translate meaning, not literal words.
            5. Tone instruction: ${request.tone.promptInstruction}.
            6. Keep proper nouns and named entities consistent.
            STRICT BATCH OUTPUT FORMAT:
            - Each input line is formatted as [ID] dialogue text.
            - Return exactly one line for every input line, in the same order.
            - Copy every numeric [ID] tag exactly; never translate, remove, renumber, or reorder it.
            - Translate only the dialogue after each tag into ${request.targetLanguage}.
            - Keep each translated dialogue on one line. Do not include timestamps, explanations, notes, markdown fences, or introductory/concluding text.
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

    private fun normalizedGeminiModel(model: String): String {
        return model.trim().removePrefix("models/")
    }

    private fun geminiApiException(
        code: Int,
        errorBody: String,
        model: String? = null
    ): IOException {
        val apiMessage = try {
            moshi.adapter(GeminiErrorEnvelope::class.java)
                .fromJson(errorBody)
                ?.error
                ?.message
                ?.trim()
        } catch (_: Exception) {
            null
        }

        if (code == 404 && apiMessage?.contains("no longer available", ignoreCase = true) == true) {
            return IOException(
                "Gemini model “${model.orEmpty()}” is not available for this API key. " +
                    "Refresh the model list and choose a current Gemini 3 model, such as gemini-3.6-flash."
            )
        }

        val detail = apiMessage?.takeIf { it.isNotBlank() }
            ?: errorBody.take(500).takeIf { it.isNotBlank() }
            ?: "Request failed."
        return IOException("Gemini API error ($code): $detail")
    }

    private fun isRetryableServerError(e: Exception): Boolean {
        val msg = e.message ?: ""
        return msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504") || msg.contains("429")
    }
}
