package com.example

import com.example.data.model.AiProvider
import com.example.data.model.TranslationRequest
import com.example.data.model.TranslationResult
import com.example.data.model.TranslationTone
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

/**
 * Regression tests for the subtitle "target language not working" bug: the AI reply could drop
 * the [N] index tags, in which case every segment silently kept its original source-language
 * text instead of the target-language translation.
 */
class SubtitleTranslatorEngineTest {

    private fun fakeFile(): SubtitleFileContent = SubtitleFileContent(
        fileName = "sample.srt",
        format = SubtitleFormat.SRT,
        segments = listOf(
            SubtitleSegment(1, "00:00:01,000 --> 00:00:02,000", "Hello, how are you?"),
            SubtitleSegment(2, "00:00:03,000 --> 00:00:04,000", "Break a leg today!"),
            SubtitleSegment(3, "00:00:05,000 --> 00:00:06,000", "That's a piece of cake.")
        )
    )

    /** Records every request and answers via [handler]. */
    private class FakeTranslationService(
        private val handler: (TranslationRequest) -> Result<TranslationResult>
    ) : TranslationService {
        val requests = mutableListOf<TranslationRequest>()

        override suspend fun translate(request: TranslationRequest): Result<TranslationResult> {
            requests += request
            return handler(request)
        }

        override suspend fun testConnection(provider: AiProvider): Result<String> =
            Result.success("ok")
    }

    private fun khmer(text: String): Result<TranslationResult> =
        Result.success(TranslationResult(translatedText = text, providerUsed = AiProvider.GEMINI))

    private fun translateAll(file: SubtitleFileContent, service: FakeTranslationService) = runBlocking {
        SubtitleTranslatorEngine(service)
            .translateSubtitles(
                subtitleFile = file,
                sourceLanguage = "English",
                targetLanguage = "Khmer (Cambodian)",
                tone = TranslationTone.AUTO,
                batchSize = 8
            )
            .toList()
    }

    @Test
    fun `tagged batch output is mapped back to the correct segments`() {
        val service = FakeTranslationService { req ->
            val out = req.text.lines().joinToString("\n") { line ->
                val tag = line.substringBefore(" ")
                "$tag ខ្មែរ-${tag.removePrefix("[").removeSuffix("]")}"
            }
            khmer(out)
        }
        val file = fakeFile()
        translateAll(file, service)

        assertEquals("ខ្មែរ-1", file.segments[0].translatedText)
        assertEquals("ខ្មែរ-2", file.segments[1].translatedText)
        assertEquals("ខ្មែរ-3", file.segments[2].translatedText)
        // Requests must be index-tagged so the model can echo the tags back.
        assertTrue(service.requests.isNotEmpty())
        service.requests.forEach { req ->
            assertTrue("Request must start with an index tag: ${req.text}", req.text.startsWith("["))
        }
    }

    @Test
    fun `untagged model output still reaches segments via positional fallback (old bug scenario)`() {
        // Simulates a model that drops every index tag but returns one line per segment.
        val service = FakeTranslationService { req ->
            val lineCount = req.text.lines().size
            khmer((1..lineCount).joinToString("\n") { "ខ្មែរ-$it" })
        }
        val file = fakeFile()
        translateAll(file, service)

        // Before the fix, all three segments silently stayed in English here.
        assertEquals("ខ្មែរ-1", file.segments[0].translatedText)
        assertEquals("ខ្មែរ-2", file.segments[1].translatedText)
        assertEquals("ខ្មែរ-3", file.segments[2].translatedText)
        assertEquals("Positional fallback should recover without extra calls", 1, service.requests.size)
    }

    @Test
    fun `segments missing from batch output are retried individually`() {
        val service = FakeTranslationService { req ->
            if (req.text.lines().size > 1) {
                khmer("[1] ខ្មែរ-1\n[3] ខ្មែរ-3") // batch drops segment 2
            } else {
                khmer("[2] ខ្មែរ-2-single") // echoed tag must be stripped from the result
            }
        }
        val file = fakeFile()
        translateAll(file, service)

        assertEquals("ខ្មែរ-1", file.segments[0].translatedText)
        assertEquals("ខ្មែរ-2-single", file.segments[1].translatedText)
        assertEquals("ខ្មែរ-3", file.segments[2].translatedText)
        assertEquals(2, service.requests.size) // 1 batch + 1 individual retry
    }

    @Test
    fun `original text is kept only when batch and single retry both fail`() {
        val service = FakeTranslationService { Result.failure(Exception("network down")) }
        val file = fakeFile()
        translateAll(file, service)

        assertEquals("Hello, how are you?", file.segments[0].translatedText)
        assertEquals("Break a leg today!", file.segments[1].translatedText)
        assertEquals("That's a piece of cake.", file.segments[2].translatedText)
        assertEquals("Expected 1 batch + 3 individual retries", 4, service.requests.size)
    }

    @Test
    fun `parser ignores bracketed numbers that are not segment indices of this batch`() {
        val engine = SubtitleTranslatorEngine(FakeTranslationService { khmer("") })
        val segments = fakeFile().segments

        val matched = engine.parseAndApplyBatchTranslation(
            segments,
            "(999) អត្ថបទកំប្លែង\n【2】 ខ្មែរ-2\n[ 1 ] ខ្មែរ-1\n[3]: ខ្មែរ-3"
        )

        assertEquals(setOf(1, 2, 3), matched)
        assertTrue(segments.none { it.translatedText.orEmpty().contains("អត្ថបទកំប្លែង") })
        assertEquals("ខ្មែរ-1", segments[0].translatedText)
        assertEquals("ខ្មែរ-2", segments[1].translatedText)
        assertEquals("ខ្មែរ-3", segments[2].translatedText)
    }

    @Test
    fun `parser returns only the segments it actually translated`() {
        val engine = SubtitleTranslatorEngine(FakeTranslationService { khmer("") })
        val segments = fakeFile().segments
        segments[1].translatedText = "ចាស់ មតិមុន" // stale value from a previous run

        val matched = engine.parseAndApplyBatchTranslation(segments, "[1] ខ្មែរ-1")

        assertEquals(setOf(1), matched)
        assertEquals("ខ្មែរ-1", segments[0].translatedText)
    }
}
