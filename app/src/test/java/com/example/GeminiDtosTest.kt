package com.example

import com.example.data.api.GeminiInteractionContent
import com.example.data.api.GeminiInteractionGenerationConfig
import com.example.data.api.GeminiInteractionRequest
import com.example.data.api.GeminiInteractionResponse
import com.example.data.api.GeminiListModelsResponse
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiDtosTest {
    /**
     * Deliberately mirrors production: KSP-generated adapters only, no
     * `KotlinJsonAdapterFactory`. If the generated adapters ever stop being found, this test
     * fails here instead of at runtime in a release build.
     */
    private val moshi = Moshi.Builder().build()

    @Test
    fun `interaction request uses current API field names`() {
        val request = GeminiInteractionRequest(
            model = "gemini-3.7-flash",
            input = listOf(GeminiInteractionContent(type = "text", text = "Hello")),
            systemInstruction = "Translate only",
            generationConfig = GeminiInteractionGenerationConfig(
                thinkingLevel = "low",
                thinkingSummaries = "none",
                maxOutputTokens = 1024
            ),
            store = false
        )

        val json = moshi.adapter(GeminiInteractionRequest::class.java).toJson(request)

        assertTrue(json.contains("\"system_instruction\":\"Translate only\""))
        assertTrue(json.contains("\"thinking_level\":\"low\""))
        assertTrue(json.contains("\"thinking_summaries\":\"none\""))
        assertTrue(json.contains("\"max_output_tokens\":1024"))
        assertTrue(json.contains("\"store\":false"))
        assertFalse(json.contains("systemInstruction"))
    }

    @Test
    fun `interaction request never emits temperature for gemini 3x`() {
        // Gemini 3.6+ ignores sampling parameters, so shipping `temperature` is at best dead
        // weight. This guards against someone re-adding the field to the DTO.
        val request = GeminiInteractionRequest(
            model = "gemini-3.7-flash",
            input = listOf(GeminiInteractionContent(type = "text", text = "Hello")),
            generationConfig = GeminiInteractionGenerationConfig(
                thinkingLevel = "minimal",
                thinkingSummaries = "none"
            )
        )

        val json = moshi.adapter(GeminiInteractionRequest::class.java).toJson(request)

        assertFalse(json.contains("temperature"))
        assertFalse(json.contains("top_p"))
        assertFalse(json.contains("top_k"))
    }

    @Test
    fun `streaming flag is serialized so the api returns server sent events`() {
        val json = moshi.adapter(GeminiInteractionRequest::class.java).toJson(
            GeminiInteractionRequest(
                model = "gemini-3.7-flash",
                input = listOf(GeminiInteractionContent(type = "text", text = "Hi")),
                store = false,
                stream = true
            )
        )

        assertTrue(json.contains("\"stream\":true"))
    }

    @Test
    fun `interaction response parses model output text and ignores thoughts`() {
        val json = """
            {
              "status": "completed",
              "steps": [
                {"type": "thought", "content": [{"type": "thought", "text": "internal reasoning"}]},
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
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("")

        assertEquals("Hola mundo", output)
    }

    @Test
    fun `models response parses supported methods and page token`() {
        val json = """
            {
              "models": [{
                "name": "models/gemini-3.7-flash",
                "displayName": "Gemini 3.7 Flash",
                "supportedGenerationMethods": ["generateContent", "countTokens"]
              }],
              "nextPageToken": "next-page"
            }
        """.trimIndent()

        val response = moshi.adapter(GeminiListModelsResponse::class.java).fromJson(json)

        assertEquals("models/gemini-3.7-flash", response?.models?.single()?.name)
        assertTrue(response?.models?.single()?.supportedGenerationMethods?.contains("generateContent") == true)
        assertEquals("next-page", response?.nextPageToken)
    }
}
