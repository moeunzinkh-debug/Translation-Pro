package com.example

import com.example.data.api.GeminiInteractionContent
import com.example.data.api.GeminiInteractionGenerationConfig
import com.example.data.api.GeminiInteractionRequest
import com.example.data.api.GeminiInteractionResponse
import com.example.data.api.GeminiListModelsResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiDtosTest {
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun `interaction request uses current API field names`() {
        val request = GeminiInteractionRequest(
            model = "gemini-3.6-flash",
            input = listOf(GeminiInteractionContent(type = "text", text = "Hello")),
            systemInstruction = "Translate only",
            generationConfig = GeminiInteractionGenerationConfig(temperature = 0.2),
            store = false
        )

        val json = moshi.adapter(GeminiInteractionRequest::class.java).toJson(request)

        assertTrue(json.contains("\"system_instruction\":\"Translate only\""))
        assertTrue(json.contains("\"generation_config\":{\"temperature\":0.2}"))
        assertTrue(json.contains("\"store\":false"))
        assertFalse(json.contains("systemInstruction"))
    }

    @Test
    fun `interaction response parses model output text`() {
        val json = """
            {
              "status": "completed",
              "steps": [
                {"type": "thought"},
                {
                  "type": "model_output",
                  "content": [{"type": "text", "text": "Hola mundo"}]
                }
              ]
            }
        """.trimIndent()

        val response = moshi.adapter(GeminiInteractionResponse::class.java).fromJson(json)
        val output = response?.steps
            .orEmpty()
            .filter { it.type == "model_output" }
            .flatMap { it.content }
            .mapNotNull { it.text }
            .joinToString("")

        assertEquals("Hola mundo", output)
    }

    @Test
    fun `models response parses supported methods and page token`() {
        val json = """
            {
              "models": [{
                "name": "models/gemini-3.6-flash",
                "displayName": "Gemini 3.6 Flash",
                "supportedGenerationMethods": ["generateContent", "countTokens"]
              }],
              "nextPageToken": "next-page"
            }
        """.trimIndent()

        val response = moshi.adapter(GeminiListModelsResponse::class.java).fromJson(json)

        assertEquals("models/gemini-3.6-flash", response?.models?.single()?.name)
        assertTrue(response?.models?.single()?.supportedGenerationMethods?.contains("generateContent") == true)
        assertEquals("next-page", response?.nextPageToken)
    }
}
