package com.example.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

interface OpenAiApiService {

    @POST
    suspend fun createChatCompletion(
        @Url fullUrl: String,
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest
    ): Response<ChatCompletionResponse>
}

interface GeminiApiService {

    /** Recommended endpoint for current and future Gemini models. */
    @POST("v1beta/interactions")
    suspend fun createInteraction(
        @Header("x-goog-api-key") apiKey: String,
        @Body request: GeminiInteractionRequest
    ): Response<GeminiInteractionResponse>

    /** Live model discovery used by the Settings model picker. */
    @GET("v1beta/models")
    suspend fun listModels(
        @Header("x-goog-api-key") apiKey: String,
        @Query("pageSize") pageSize: Int = 1000,
        @Query("pageToken") pageToken: String? = null
    ): Response<GeminiListModelsResponse>
}
