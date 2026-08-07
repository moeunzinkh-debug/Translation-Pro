package com.example.data.service

import com.example.data.model.AiProvider
import com.example.data.model.TranslationRequest
import com.example.data.model.TranslationResult

interface TranslationService {
    suspend fun translate(request: TranslationRequest): Result<TranslationResult>
    suspend fun testConnection(provider: AiProvider): Result<String>
}
