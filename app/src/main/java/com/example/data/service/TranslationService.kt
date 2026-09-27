package com.example.data.service

import com.example.data.model.AiProvider
import com.example.data.model.TranslationRequest
import com.example.data.model.TranslationResult

interface TranslationService {
    suspend fun translate(request: TranslationRequest): Result<TranslationResult>

    /**
     * Streams the translation, invoking [onPartial] with the full text accumulated so far as
     * each chunk of tokens arrives. Implementations that have no streaming support (or non-
     * streaming providers) simply fall back to [translate] and call [onPartial] once.
     */
    suspend fun translateStreaming(
        request: TranslationRequest,
        onPartial: (String) -> Unit
    ): Result<TranslationResult>

    suspend fun testConnection(provider: AiProvider): Result<String>
}
