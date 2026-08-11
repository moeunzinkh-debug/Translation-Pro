package com.example

import com.example.data.subtitle.SubtitleFormat
import com.example.data.subtitle.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParserTest {

    @Test
    fun `srt keeps original cue numbers during parse and serialization`() {
        val file = SubtitleParser.parse(
            "movie.srt",
            """
            10
            00:00:01,000 --> 00:00:02,000
            First line

            20
            00:00:03,000 --> 00:00:04,000
            Second line
            """.trimIndent()
        )

        assertEquals(SubtitleFormat.SRT, file.format)
        assertEquals(listOf(10, 20), file.segments.map { it.index })
        assertEquals("10", file.segments[0].cueIdentifier)
        assertTrue(SubtitleParser.serialize(file).startsWith("10\n00:00:01,000"))
    }

    @Test
    fun `vtt keeps numeric and nonnumeric cue identifiers`() {
        val file = SubtitleParser.parse(
            "captions.vtt",
            """
            WEBVTT

            first-cue
            00:00:01.000 --> 00:00:02.000
            Hello

            42
            00:00:03.000 --> 00:00:04.000
            World
            """.trimIndent()
        )

        assertEquals(SubtitleFormat.VTT, file.format)
        assertEquals(listOf("first-cue", "42"), file.segments.map { it.cueIdentifier })
        val serialized = SubtitleParser.serialize(file)
        assertTrue(serialized.contains("first-cue\n00:00:01.000"))
        assertTrue(serialized.contains("42\n00:00:03.000"))
    }
}
