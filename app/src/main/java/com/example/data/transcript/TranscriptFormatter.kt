package com.example.data.transcript

/** A contiguous turn spoken by one person in an audio transcript. */
data class TranscriptChunk(
    val speaker: String,
    val text: String
)

/**
 * Parses and formats the speaker-labelled transcript returned by Gemini.
 *
 * The model is asked to use `[Speaker 1]` / `[Speaker 2]` markers. This parser also accepts
 * `Speaker 1:` and `Person 1:` so a small formatting variation does not remove speaker chunks
 * from the UI. Consecutive lines for the same speaker are merged into one chunk.
 */
object TranscriptFormatter {
    private val speakerMarker = Regex(
        """^\s*(?:\[(?:Speaker|Person)\s+([A-Za-z0-9_-]+)\]|(?:Speaker|Person)\s+([A-Za-z0-9_-]+)\s*:?)\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(transcript: String): List<TranscriptChunk> {
        val chunks = mutableListOf<TranscriptChunk>()
        var currentSpeaker: String? = null
        val currentLines = mutableListOf<String>()

        fun flushCurrentChunk() {
            val speaker = currentSpeaker
            val text = currentLines.joinToString("\n").trim()
            if (speaker != null && text.isNotBlank()) {
                chunks += TranscriptChunk(speaker = speaker, text = text)
            }
            currentLines.clear()
        }

        transcript
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lineSequence()
            .forEach { rawLine ->
                val line = rawLine.trim()
                val marker = speakerMarker.matchEntire(line)

                if (marker != null) {
                    val speakerId = marker.groupValues[1]
                        .ifBlank { marker.groupValues[2] }
                    val speaker = "Speaker $speakerId"

                    if (currentSpeaker != null && currentSpeaker != speaker) {
                        flushCurrentChunk()
                    }
                    currentSpeaker = speaker

                    val firstLine = marker.groupValues[3].trim()
                    if (firstLine.isNotBlank()) currentLines += firstLine
                } else if (line.isNotBlank() && currentSpeaker != null) {
                    currentLines += line
                }
            }

        flushCurrentChunk()

        // Older transcripts may not have speaker markers. Keep them visible as one chunk rather
        // than showing an empty transcript, while new Gemini responses will use diarized chunks.
        if (chunks.isEmpty() && transcript.isNotBlank()) {
            return listOf(TranscriptChunk("Speaker 1", transcript.trim()))
        }
        return chunks
    }

    /** Formats chunks in a stable form for copy and export. */
    fun format(chunks: List<TranscriptChunk>): String {
        return chunks.joinToString("\n\n") { chunk ->
            "[${chunk.speaker}]\n${chunk.text}"
        }
    }
}
