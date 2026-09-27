package com.example.data.api

import okhttp3.ResponseBody
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

    /**
     * Same endpoint, but with `"stream": true` in the body. The response is a
     * `text/event-stream`, so Retrofit hands back the raw body and we parse the SSE frames
     * ourselves. This is what makes the app feel instant: the first tokens are painted
     * milliseconds after the model starts emitting, instead of after the whole answer.
     */
    @POST("v1beta/interactions")
    suspend fun createInteractionStream(
        @Header("x-goog-api-key") apiKey: String,
        @Body request: GeminiInteractionRequest
    ): Response<ResponseBody>

    /** Live model discovery used by the Settings model picker. */
    @GET("v1beta/models")
    suspend fun listModels(
        @Header("x-goog-api-key") apiKey: String,
        @Query("pageSize") pageSize: Int = 100,
        @Query("pageToken") pageToken: String? = null
    ): Response<GeminiListModelsResponse>
}
