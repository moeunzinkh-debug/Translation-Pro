package com.example.data.repository

import android.util.Base64
import com.example.BuildConfig
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * An HTTP error that carries its status code.
 *
 * This exists so the retry policy can tell a *transient* failure (429 / 5xx / socket error)
 * apart from a *deterministic* one (400 bad request, 401 bad key, 404 unknown model). The old
 * code funnelled every API failure into a plain `IOException` and therefore retried all of
 * them after a 1s sleep — which doubled the wait on exactly the requests that could never
 * succeed.
 */
class ApiHttpException(
    val statusCode: Int,
    message: String
) : IOException(message)

class TranslationRepository(
    private val settingsRepository: SecureSettingsRepository
) : TranslationService {

    // ── Translates the source text, streaming tokens when the provider supports it. ────────
    override suspend fun translate(request: TranslationRequest): Result<TranslationResult> =
        withContext(Dispatchers.IO) { runWithRetries { doTranslate(request) } }

    override suspend fun translateStreaming(
        request: TranslationRequest,
        onPartial: (String) -> Unit
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        if (settingsRepository.getSelectedProvider() != AiProvider.GEMINI) {
            // No SSE support on the OpenAI-compatible providers yet — one-shot is still
            // correct, and we still notify once so the UI behaves identically.
            return@withContext runWithRetries {
                doTranslate(request).also { onPartial(it.translatedText) }
            }
        }
        runWithRetries { translateViaGeminiStreaming(request, onPartial) }
    }

    private suspend fun doTranslate(request: TranslationRequest): TranslationResult {
        val provider = settingsRepository.getSelectedProvider()
        val apiKey = settingsRepository.getApiKeyForProvider(provider)

        if (apiKey.isBlank()) {
            throw IllegalArgumentException(
                "API Key for ${provider.displayName} is missing. Please configure it in Settings."
            )
        }

        return when (provider) {
            AiProvider.SEA_LION -> translateViaOpenAiCompatible(
                request = request,
                apiKey = apiKey,
                endpointUrl = settingsRepository.chatCompletionsUrl(
                    baseUrl = settingsRepository.getSeaLionBaseUrl()
                ),
                model = settingsRepository.getSeaLionModel(),
                label = "Sea-Lion API"
            )
            AiProvider.GEMINI -> translateViaGemini(request, apiKey)
            AiProvider.CHATGPT -> translateViaOpenAiCompatible(
                request = request,
                apiKey = apiKey,
                endpointUrl = "https://api.openai.com/v1/chat/completions",
                model = settingsRepository.getChatGptModel(),
                label = "ChatGPT API"
            )
            AiProvider.CUSTOM -> translateViaOpenAiCompatible(
                request = request,
                apiKey = apiKey,
                endpointUrl = settingsRepository.chatCompletionsUrl(
                    baseUrl = settingsRepository.getCustomBaseUrl()
                ),
                model = settingsRepository.getCustomModel(),
                label = "Custom API"
            )
        }
    }

    override suspend fun testConnection(provider: AiProvider): Result<String> =
        withContext(Dispatchers.IO) {
            val apiKey = settingsRepository.getApiKeyForProvider(provider)
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API Key is empty."))
            }

            val testRequest = TranslationRequest(
                sourceLanguage = "English",
                targetLanguage = "Spanish",
                text = "Hello world",
                isSubtitle = true
            )

            runWithRetries {
                val result = when (provider) {
                    AiProvider.SEA_LION -> translateViaOpenAiCompatible(
                        request = testRequest,
                        apiKey = apiKey,
                        endpointUrl = settingsRepository.chatCompletionsUrl(
                            baseUrl = settingsRepository.getSeaLionBaseUrl()
                        ),
                        model = settingsRepository.getSeaLionModel(),
                        label = "Sea-Lion API"
                    )
                    AiProvider.GEMINI -> translateViaGemini(testRequest, apiKey)
                    AiProvider.CHATGPT -> translateViaOpenAiCompatible(
                        request = testRequest,
                        apiKey = apiKey,
                        endpointUrl = "https://api.openai.com/v1/chat/completions",
                        model = settingsRepository.getChatGptModel(),
                        label = "ChatGPT API"
                    )
                    AiProvider.CUSTOM -> translateViaOpenAiCompatible(
                        request = testRequest,
                        apiKey = apiKey,
                        endpointUrl = settingsRepository.chatCompletionsUrl(
                            baseUrl = settingsRepository.getCustomBaseUrl()
                        ),
                        model = settingsRepository.getCustomModel(),
                        label = "Custom API"
                    )
                }
                "Connection successful! Response: \"${result.translatedText.trim()}\""
            }
        }

    // ── OpenAI-compatible providers: Sea-Lion, ChatGPT, Custom ───────────────────────────
    private suspend fun translateViaOpenAiCompatible(
        request: TranslationRequest,
        apiKey: String,
        endpointUrl: String,
        model: String,
        label: String
    ): TranslationResult {
        val chatRequest = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", buildSystemPrompt(request)),
                ChatMessage("user", buildUserContent(request))
            ),
            temperature = temperatureFor(request)
        )

        val response = openAiApiService.createChatCompletion(
            fullUrl = endpointUrl,
            authorization = "Bearer $apiKey",
            request = chatRequest
        )

        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string().orEmpty()
            throw ApiHttpException(response.code(), "$label error (${response.code()}): $errBody")
        }

        val rawText = response.body()?.choices?.firstOrNull()?.message?.content
            ?: throw IOException("Empty response from $label")

        return parseTranslationOutput(rawText, request, providerForLabel(label))
    }

    // ── Gemini: non-streaming path (used by subtitles, connection tests) ─────────────────
    private suspend fun translateViaGemini(
        request: TranslationRequest,
        apiKey: String
    ): TranslationResult {
        var currentApiKey = apiKey
        var currentKeyId = settingsRepository.getActiveGeminiKey()?.id
        var lastException: Exception? = null

        for (attempt in 0 until MAX_KEY_ROTATIONS) {
            try {
                val rawText = createGeminiInteraction(
                    request = request,
                    apiKey = currentApiKey
                )
                settingsRepository.recordGeminiRequest()
                return parseTranslationOutput(rawText, request, AiProvider.GEMINI)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                // 401/403 = the key itself is bad, 429 = the key is out of budget. Both are
                // worth failing over to another key; every other failure is not.
                if (shouldRotateKey(e) && currentKeyId != null) {
                    val nextKey = settingsRepository.rotateToNextGeminiKey(currentKeyId!!)
                    if (nextKey != null) {
                        currentApiKey = nextKey.value
                        currentKeyId = nextKey.id
                        continue
                    }
                }
                throw e
            }
        }

        throw lastException ?: IOException("All Gemini API keys exhausted.")
    }

    // ── Gemini: streaming path (instant mode) ────────────────────────────────────────────
    private suspend fun translateViaGeminiStreaming(
        request: TranslationRequest,
        apiKey: String,
        onPartial: (String) -> Unit
    ): TranslationResult {
        var currentApiKey = apiKey
        var currentKeyId = settingsRepository.getActiveGeminiKey()?.id
        var lastException: Exception? = null

        for (attempt in 0 until MAX_KEY_ROTATIONS) {
            try {
                val rawText = streamGeminiInteraction(
                    request = request,
                    apiKey = currentApiKey,
                    onPartial = onPartial
                )
                settingsRepository.recordGeminiRequest()
                return parseTranslationOutput(rawText, request, AiProvider.GEMINI)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (shouldRotateKey(e) && currentKeyId != null) {
                    val nextKey = settingsRepository.rotateToNextGeminiKey(currentKeyId!!)
                    if (nextKey != null) {
                        currentApiKey = nextKey.value
                        currentKeyId = nextKey.id
                        continue
                    }
                }
                throw e
            }
        }

        throw lastException ?: IOException("All Gemini API keys exhausted.")
    }

    /** Performs one non-streaming Interactions call, honouring [onText] as the final text. */
    private suspend fun createGeminiInteraction(
        request: TranslationRequest,
        apiKey: String,
        onText: (String) -> Unit = {},
        dropThinkingLevel: Boolean = false
    ): String {
        val model = normalizedGeminiModel(settingsRepository.getGeminiModel())
        val body = buildInteractionRequest(
            request = request,
            model = model,
            stream = false,
            dropThinkingLevel = dropThinkingLevel
        )

        val response = geminiApiService.createInteraction(apiKey, body)

        if (!response.isSuccessful) {
            throw ApiHttpException(
                response.code(),
                geminiApiException(
                    code = response.code(),
                    errorBody = response.errorBody()?.string().orEmpty(),
                    model = model
                ).message.orEmpty()
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

        if (text.isBlank()) throw IOException("Gemini returned an empty translation.")

        onText(text)
        return text
    }

    /**
     * Performs one streaming Interactions call, pushing the accumulated text into [onPartial]
     * as each SSE frame arrives.
     *
     * If the server answers without emitting any `step.delta` frames (older revision, or a
     * proxy that strips SSE) we transparently fall back to a single non-streaming call, so the
     * user still gets their translation instead of an empty result.
     */
    private suspend fun streamGeminiInteraction(
        request: TranslationRequest,
        apiKey: String,
        onPartial: (String) -> Unit
    ): String {
        val model = normalizedGeminiModel(settingsRepository.getGeminiModel())
        val body = buildInteractionRequest(
            request = request,
            model = model,
            stream = true,
            dropThinkingLevel = false
        )

        val response = geminiApiService.createInteractionStream(apiKey, body)

        if (!response.isSuccessful) {
            val code = response.code()
            val errBody = response.errorBody()?.string().orEmpty()
            val detail = geminiApiException(code = code, errorBody = errBody, model = model)
                .message.orEmpty()

            // A model that rejects our thinking level (e.g. "minimal" on 3.7/3.8) should not
            // cost the user their translation — drop the override and try once more.
            if (code == 400 && detail.contains("thinking", ignoreCase = true)) {
                return createGeminiInteraction(
                    request = request,
                    apiKey = apiKey,
                    onText = onPartial,
                    dropThinkingLevel = true
                )
            }
            throw ApiHttpException(code, detail)
        }

        val rawBody = response.body() ?: throw IOException("Gemini returned an empty stream.")
        val accumulated = StringBuilder()

        rawBody.use { body ->
            val source = body.source()
            var eventName = ""
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break

                if (line.isEmpty()) {          // end of an SSE frame
                    eventName = ""
                    continue
                }
                if (line.startsWith(":")) continue   // comment / keep-alive
                if (line.startsWith("event:")) {
                    eventName = line.removePrefix("event:").trim()
                    continue
                }
                if (!line.startsWith("data:")) continue   // `id:` / `retry:`

                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                // The terminal event carries only the final usage block. Stop here instead of
                // blocking until the server closes the socket (and risking the read timeout).
                if (eventName == "interaction.completed") break

                val chunk = extractTextDelta(payload) ?: continue
                accumulated.append(chunk)
                onPartial(accumulated.toString())
            }
        }

        if (accumulated.isBlank()) {
            return createGeminiInteraction(
                request = request,
                apiKey = apiKey,
                onText = onPartial
            )
        }
        return accumulated.toString()
    }

    /**
     * Pulls the visible text out of one `step.delta` frame.
     *
     * `delta.type == "thought"` carries the model's private reasoning, which must never be
     * shown to the user, so it is filtered out here.
     */
    private fun extractTextDelta(payload: String): String? {
        return try {
            val delta = JSONObject(payload).optJSONObject("delta") ?: return null
            if (!delta.optString("type").equals("text", ignoreCase = true)) return null
            delta.optString("text").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildInteractionRequest(
        request: TranslationRequest,
        model: String,
        stream: Boolean,
        dropThinkingLevel: Boolean = false
    ) = GeminiInteractionRequest(
        model = model,
        input = listOf(GeminiInteractionContent(type = "text", text = buildUserContent(request))),
        systemInstruction = buildSystemPrompt(request),
        generationConfig = instantGenerationConfig(request, model, dropThinkingLevel),
        store = false,
        stream = stream
    )

    // ── Model discovery for the Settings picker ──────────────────────────────────────────
    /**
     * Loads every model page from Google's live Models API and returns the models that can
     * actually produce text through the Interactions API. This avoids a hard-coded list
     * going stale when Google changes model availability.
     */
    suspend fun listGeminiModels(): Result<List<GeminiModel>> = withContext(Dispatchers.IO) {
        try {
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
                    pageSize = MODEL_PAGE_SIZE,
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
                        } && isTextTranslationModel(model.name)
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

            val models = allModels
                .distinctBy { it.id }
                .sortedWith(
                    compareBy<GeminiModel> { model ->
                        PREFERRED_MODEL_ORDER.indexOf(model.id)
                            .let { if (it == -1) Int.MAX_VALUE else it }
                    }.thenBy { it.displayName.lowercase() }
                        .thenBy { it.id }
                )

            if (models.isEmpty()) {
                throw IOException("No Gemini text-generation models are available for this API key.")
            }
            Result.success(models)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Audio transcription ──────────────────────────────────────────────────────────────
    /** Sends an audio clip to Gemini's multimodal Interactions API and returns plain transcript text. */
    suspend fun transcribe(audio: ByteArray, mimeType: String, languageHint: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = settingsRepository.getGeminiApiKey()
                if (apiKey.isBlank()) throw IllegalArgumentException("Add a Gemini API key in Settings first.")

                val model = normalizedGeminiModel(settingsRepository.getGeminiModel())
                var currentApiKey = apiKey
                var currentKeyId = settingsRepository.getActiveGeminiKey()?.id
                var lastException: Exception? = null

                val prompt =
                    "Transcribe this audio accurately${if (languageHint.isBlank()) "" else " in $languageHint"}. " +
                        "Return only the transcript, with natural paragraph breaks."

                for (attempt in 0 until MAX_KEY_ROTATIONS) {
                    try {
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
                            generationConfig = GeminiInteractionGenerationConfig(
                                thinkingLevel = fastestThinkingLevel(model),
                                thinkingSummaries = "none",
                                maxOutputTokens = 32_768
                            ),
                            store = false
                        )

                        val response = geminiApiService.createInteraction(currentApiKey, request)
                        if (!response.isSuccessful) {
                            val code = response.code()
                            val detail = geminiApiException(
                                code = code,
                                errorBody = response.errorBody()?.string().orEmpty(),
                                model = model
                            ).message.orEmpty()
                            if (shouldRotateKey(ApiHttpException(code, detail)) && currentKeyId != null) {
                                val nextKey = settingsRepository.rotateToNextGeminiKey(currentKeyId!!)
                                if (nextKey != null) {
                                    currentApiKey = nextKey.value
                                    currentKeyId = nextKey.id
                                    continue
                                }
                            }
                            throw ApiHttpException(code, detail)
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
                        return@withContext Result.success(text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastException = e
                        if (shouldRotateKey(e) && currentKeyId != null) {
                            val nextKey = settingsRepository.rotateToNextGeminiKey(currentKeyId!!)
                            if (nextKey != null) {
                                currentApiKey = nextKey.value
                                currentKeyId = nextKey.id
                                continue
                            }
                        }
                        throw e
                    }
                }

                throw lastException ?: IOException("All Gemini API keys exhausted.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── Prompts ─────────────────────────────────────────────────────────────────────────
    private fun buildSystemPrompt(request: TranslationRequest): String {
        if (request.isSubtitle) {
            return """
            You are a professional media subtitle translator.
            Task:
            1. Translate every dialogue segment accurately into ${request.targetLanguage}.
            2. Source Language: ${request.sourceLanguage}.
            3. Respect slang, idioms, and cultural context. Translate meaning, not literal words.
            4. Tone instruction: ${request.tone.promptInstruction}.
            5. Keep proper nouns and named entities consistent.
            6. TAG PRESERVATION (CRITICAL): Every input segment starts with an index tag like [7]. Your output MUST repeat that exact same tag (same number, same brackets) as the very first thing on its translated line, in the same order. Output exactly one tagged line per input segment — never renumber, merge, split, skip, or add segments.
            7. STRICT SUBTITLE CONSTRAINT: Output ONLY the tagged translated lines. Do NOT add timestamps, explanatory notes, or intro/outro conversational fluff.
            8. SPEED: Reply with the translations only. Never restate the task or add commentary.
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
        7. SPEED: Answer immediately with the translation. Never restate the task, never explain your process, and never add notes unless the text genuinely contains an idiom or piece of slang.
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
        4. SPEED: Output the new translation only, with no preamble.
        """.trimIndent()
    }

    private fun buildUserContent(request: TranslationRequest): String = request.text

    // ── Instant-mode generation settings ─────────────────────────────────────────────────
    private fun instantGenerationConfig(
        request: TranslationRequest,
        model: String,
        dropThinkingLevel: Boolean = false
    ) = GeminiInteractionGenerationConfig(
        thinkingLevel = if (dropThinkingLevel) null else fastestThinkingLevel(model),
        thinkingSummaries = "none",
        maxOutputTokens = outputTokenBudget(request.text),
        // `temperature` is ignored by Gemini 3.6+, so a per-attempt seed is the only lever
        // left to make a re-tap actually produce a different wording.
        seed = if (request.alternativeAttempt > 0) {
            request.alternativeAttempt * 7_919 + request.text.hashCode()
        } else {
            null
        }
    )

    /**
     * The lowest thinking level this model accepts.
     *
     * Gemini 3.7 / 3.8 Flash reject `"minimal"` with a 400, so they get `"low"` — which is
     * still the fastest setting they offer. Everything else gets `"minimal"`, which matches
     * the old "no thinking" behaviour and is what makes short translations feel instant.
     */
    private fun fastestThinkingLevel(model: String): String {
        val match = GEMINI_VERSION.find(model)
        val major = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val minor = match?.groupValues?.getOrNull(2)?.toIntOrNull()
        val supportsMinimal = !(major == 3 && (minor == 7 || minor == 8))
        return if (supportsMinimal) "minimal" else "low"
    }

    /** Generous headroom so long text is never truncated, but still bounded against runaway output. */
    private fun outputTokenBudget(text: String): Int =
        ((text.length * 3) + 512).coerceIn(1_024, 32_768)

    // Low temperature keeps the first translation precise; a higher temperature on re-taps
    // encourages the AI to come up with a genuinely different alternative. (Only honoured by
    // the OpenAI-compatible providers — Gemini 3.6+ ignores sampling parameters.)
    private fun temperatureFor(request: TranslationRequest): Double {
        return if (request.alternativeAttempt > 0) 0.75 else 0.2
    }

    // ── Output parsing ──────────────────────────────────────────────────────────────────
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
            slangNotes = cleanText.substring(noteIndex)
                .removePrefix("[Note:")
                .removeSuffix("]")
                .trim()
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

    private fun providerForLabel(label: String): AiProvider = when (label) {
        "Sea-Lion API" -> AiProvider.SEA_LION
        "ChatGPT API" -> AiProvider.CHATGPT
        "Custom API" -> AiProvider.CUSTOM
        else -> AiProvider.CUSTOM
    }

    // ── Retry / error handling ───────────────────────────────────────────────────────────
    /**
     * Runs [block], retrying only failures that could plausibly succeed on a second try.
     *
     * Deterministic errors (400 / 401 / 403 / 404) fail immediately: retrying them used to
     * add a full second request plus a 1s sleep before the user ever saw the error.
     */
    private suspend fun <T> runWithRetries(block: suspend () -> T): Result<T> {
        var attempt = 0
        var lastError: Throwable? = null

        while (attempt < MAX_ATTEMPTS) {
            attempt++
            try {
                return Result.success(block())
            } catch (e: CancellationException) {
                // Never swallow cancellation — it would leave a dead coroutine "loading".
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_ATTEMPTS && isRetryable(e)) {
                    delay(RETRY_BACKOFF_MS * attempt)
                } else {
                    break
                }
            }
        }
        return Result.failure(lastError ?: IOException("Request failed."))
    }

    private fun isRetryable(e: Throwable): Boolean = when (e) {
        is ApiHttpException -> e.statusCode == 429 || e.statusCode in 500..599
        is IOException -> true
        else -> false
    }

    private fun shouldRotateKey(e: Throwable): Boolean {
        val code = (e as? ApiHttpException)?.statusCode
        return code == 429 || code == 403 || code == 401
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
                    "Refresh the model list and choose a current Gemini 3 model, such as gemini-3.7-flash."
            )
        }

        val detail = apiMessage?.takeIf { it.isNotBlank() }
            ?: errorBody.take(500).takeIf { it.isNotBlank() }
            ?: "Request failed."
        return IOException("Gemini API error ($code): $detail")
    }

    /**
     * The Models API advertises far more than text generation (embeddings, image, video, TTS,
     * live). Surfacing those in a translation picker only produces failed requests, so they
     * are filtered out here.
     */
    private fun isTextTranslationModel(modelName: String): Boolean {
        val id = modelName.lowercase()
        return NON_TEXT_MODEL_MARKERS.none { id.contains(it) }
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
        private const val MAX_KEY_ROTATIONS = 3
        private const val RETRY_BACKOFF_MS = 500L
        private const val MODEL_PAGE_SIZE = 100

        private val GEMINI_VERSION = Regex("""gemini-(\d+)\.(\d+)""")

        private val NON_TEXT_MODEL_MARKERS = listOf(
            "embedding", "imagen", "veo", "lyria", "robotics",
            "-image", "-tts", "aqa", "retrieval", "-live"
        )

        /**
         * Fastest first, so the picker opens on a genuinely instant model. `gemini-3.8-flash`
         * is the most capable of the line but the slowest, so it is deliberately last of the
         * Flash models.
         */
        private val PREFERRED_MODEL_ORDER = listOf(
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite"
        )

        /**
         * Moshi with KSP-generated adapters only. `KotlinJsonAdapterFactory` is deliberately
         * NOT registered: every DTO is `@JsonClass(generateAdapter = true)`, so the reflective
         * factory is pure startup cost and an R8 liability.
         */
        private val moshi: Moshi = Moshi.Builder().build()

        /**
         * A single process-wide client. Building one per `TranslationRepository` (i.e. per
         * Activity creation) threw away the connection pool and forced a full TCP + TLS
         * handshake before the first token after every configuration change.
         */
        private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    // Was hard-coded to BODY, which logged full request/response bodies —
                    // including the API key headers — to logcat in release builds.
                    level = if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BASIC
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
                    redactHeader("Authorization")
                    redactHeader("x-goog-api-key")
                }
            )
            .build()

        private val openAiApiService: OpenAiApiService by lazy {
            Retrofit.Builder()
                .baseUrl("https://api.openai.com/v1/")
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
    }
}
