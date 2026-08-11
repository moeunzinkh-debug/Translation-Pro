package com.example

import com.example.data.model.AiProvider
import com.example.data.model.TranslationRequest
import com.example.data.model.TranslationResult
import com.example.data.service.TranslationService
import com.example.data.subtitle.SubtitleFileContent
import com.example.data.subtitle.SubtitleFormat
import com.example.data.subtitle.SubtitleSegment
import com.example.data.subtitle.SubtitleTranslatorEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslatorEngineTest {

    @Test
    fun `tagged batch response is applied to the matching subtitle segments`() = runBlocking {
        val service = FakeTranslationService(
            responses = listOf("[1] Hello\n[2] Goodbye")
        )
        val file = subtitleFile(
            SubtitleSegment(1, "00:00:01,000 --> 00:00:02,000", "你好"),
            SubtitleSegment(2, "00:00:02,000 --> 00:00:03,000", "再见")
        )

        val progress = SubtitleTranslatorEngine(service)
            .translateSubtitles(
                subtitleFile = file,
                sourceLanguage = "Mandarin (Simplified)",
                targetLanguage = "English",
                batchSize = 5
            )
            .toList()

        assertEquals("Hello", file.segments[0].translatedText)
        assertEquals("Goodbye", file.segments[1].translatedText)
        assertEquals("English", service.requests.single().targetLanguage)
        assertTrue(service.requests.single().text.contains("[1] 你好"))
        assertEquals(2, progress.last().processedSegments)
        assertTrue(progress.last().isComplete)
    }

    @Test
    fun `plain one-line-per-segment response is still mapped instead of copying source text`() = runBlocking {
        val service = FakeTranslationService(
            responses = listOf("Hello\nGoodbye")
        )
        val file = subtitleFile(
            SubtitleSegment(1, "00:00:01,000 --> 00:00:02,000", "你好"),
            SubtitleSegment(2, "00:00:02,000 --> 00:00:03,000", "再见")
        )

        SubtitleTranslatorEngine(service)
            .translateSubtitles(file, "Mandarin (Simplified)", "English", batchSize = 5)
            .toList()

        assertEquals("Hello", file.segments[0].translatedText)
        assertEquals("Goodbye", file.segments[1].translatedText)
        // A valid sequential response does not need expensive single-line retries.
        assertEquals(1, service.requests.size)
    }

    @Test
    fun `unexpected numeric tags in a sequential response are not shown in the subtitle text`() = runBlocking {
        val service = FakeTranslationService(
            responses = listOf("[99] Hello\n[100] Goodbye")
        )
        val file = subtitleFile(
            SubtitleSegment(1, "00:00:01,000 --> 00:00:02,000", "你好"),
            SubtitleSegment(2, "00:00:02,000 --> 00:00:03,000", "再见")
        )

        SubtitleTranslatorEngine(service)
            .translateSubtitles(file, "Mandarin (Simplified)", "English", batchSize = 5)
            .toList()

        assertEquals("Hello", file.segments[0].translatedText)
        assertEquals("Goodbye", file.segments[1].translatedText)
        assertEquals(1, service.requests.size)
    }

    @Test
    fun `missing batch item is retried with its target language and segment tag`() = runBlocking {
        val service = FakeTranslationService(
            responses = listOf(
                "[1] Hello", // malformed batch response: segment 2 is missing
                "[2] Goodbye" // per-segment fallback
            )
        )
        val file = subtitleFile(
            SubtitleSegment(1, "00:00:01,000 --> 00:00:02,000", "你好"),
            SubtitleSegment(2, "00:00:02,000 --> 00:00:03,000", "再见")
        )

        SubtitleTranslatorEngine(service)
            .translateSubtitles(file, "Mandarin (Simplified)", "English", batchSize = 5)
            .toList()

        assertEquals("Hello", file.segments[0].translatedText)
        assertEquals("Goodbye", file.segments[1].translatedText)
        assertEquals("English", service.requests[1].targetLanguage)
        assertEquals("[2] 再见", service.requests[1].text)
    }

    private fun subtitleFile(vararg segments: SubtitleSegment) = SubtitleFileContent(
        fileName = "test.srt",
        format = SubtitleFormat.SRT,
        segments = segments.toList()
    )

    private class FakeTranslationService(
        private val responses: List<String>
    ) : TranslationService {
        val requests = mutableListOf<TranslationRequest>()
        private var responseIndex = 0

        override suspend fun translate(request: TranslationRequest): Result<TranslationResult> {
            requests += request
            val response = responses.getOrNull(responseIndex++)
                ?: return Result.failure(IllegalStateException("No fake response configured"))
            return Result.success(
                TranslationResult(
                    translatedText = response,
                    providerUsed = AiProvider.SEA_LION
                )
            )
        }

        override suspend fun testConnection(provider: AiProvider): Result<String> =
            Result.success("ok")
    }
}
