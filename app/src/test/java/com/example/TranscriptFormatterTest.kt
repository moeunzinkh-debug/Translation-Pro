package com.example

import com.example.data.transcript.TranscriptFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFormatterTest {

    @Test
    fun `speaker markers create separate chunks and preserve multiline speech`() {
        val chunks = TranscriptFormatter.parse(
            """
            [Speaker 1]
            Hello, are you ready?
            I have been waiting.

            [Speaker 2]
            Yes, I am ready.
            """.trimIndent()
        )

        assertEquals(2, chunks.size)
        assertEquals("Speaker 1", chunks[0].speaker)
        assertEquals("Hello, are you ready?\nI have been waiting.", chunks[0].text)
        assertEquals("Speaker 2", chunks[1].speaker)
        assertEquals("Yes, I am ready.", chunks[1].text)
    }

    @Test
    fun `same speaker stays in one chunk while a returning speaker starts a new turn`() {
        val chunks = TranscriptFormatter.parse(
            "[Speaker 1] First line\n[Speaker 1] More from A\n[Speaker 2] Reply\n[Speaker 1] A again"
        )

        assertEquals(3, chunks.size)
        assertEquals("First line\nMore from A", chunks[0].text)
        assertEquals("Reply", chunks[1].text)
        assertEquals("A again", chunks[2].text)
        assertEquals("Speaker 1", chunks[2].speaker)
    }

    @Test
    fun `unlabelled legacy transcript remains visible as one fallback chunk`() {
        val chunks = TranscriptFormatter.parse("A transcript without diarization")

        assertEquals(1, chunks.size)
        assertEquals("Speaker 1", chunks.single().speaker)
        assertTrue(chunks.single().text.contains("without diarization"))
    }

    @Test
    fun `format uses a stable labelled export format`() {
        val chunks = TranscriptFormatter.parse("Speaker 1: Hello\n\nPerson 2: Hi")

        assertEquals(
            "[Speaker 1]\nHello\n\n[Speaker 2]\nHi",
            TranscriptFormatter.format(chunks)
        )
    }
}
