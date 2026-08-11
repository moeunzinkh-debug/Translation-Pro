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
 * `Speaker 1:`, `Person 1:`, and ASR labels such as `spk_1` so a small formatting variation does
 * not remove speaker chunks from the UI. Consecutive lines for the same speaker are merged into one chunk.
 */
object TranscriptFormatter {
    private val bracketSpeakerMarker = Regex(
        """^\s*\[(?:Speaker|Person)\s+([A-Za-z0-9_-]+)\]\s*:?\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val namedSpeakerMarker = Regex(
        """^\s*(?:Speaker|Person)\s+([A-Za-z0-9_-]+)\s*:?\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val shortSpeakerMarker = Regex(
        """^\s*\[?spk[_-]?([A-Za-z0-9_-]+)\]?\s*:?\s*(.*)$""",
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
                val marker = bracketSpeakerMarker.matchEntire(line)
                    ?: namedSpeakerMarker.matchEntire(line)
                    ?: shortSpeakerMarker.matchEntire(line)

                if (marker != null) {
                    val speaker = "Speaker ${marker.groupValues[1]}"

                    if (currentSpeaker != null && currentSpeaker != speaker) {
                        flushCurrentChunk()
                    }
                    currentSpeaker = speaker

                    val firstLine = marker.groupValues[2].trim()
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
