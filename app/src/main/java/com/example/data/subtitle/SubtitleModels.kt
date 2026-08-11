package com.example.data.subtitle

enum class SubtitleFormat(val extension: String, val displayName: String) {
    SRT("srt", "SubRip Subtitle (.srt)"),
    VTT("vtt", "WebVTT Subtitle (.vtt)")
}

data class SubtitleSegment(
    /** Numeric internal ID used by the translation batch protocol. */
    val index: Int,
    val timecode: String,
    val originalText: String,
    var translatedText: String? = null,
    /** Original cue identifier, including non-numeric WebVTT identifiers when present. */
    val cueIdentifier: String? = null
)

data class SubtitleFileContent(
    val fileName: String,
    val format: SubtitleFormat,
    val segments: List<SubtitleSegment>
)

data class SubtitleProgress(
    val currentBatch: Int,
    val totalBatches: Int,
    val processedSegments: Int,
    val totalSegments: Int,
    val isComplete: Boolean = false,
    val error: String? = null
)
