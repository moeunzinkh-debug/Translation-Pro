package com.example.data.subtitle

object SubtitleParser {

    fun parse(fileName: String, content: String): SubtitleFileContent {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val format = if (extension == "vtt" || content.trimStart().startsWith("WEBVTT")) {
            SubtitleFormat.VTT
        } else {
            SubtitleFormat.SRT
        }

        val segments = if (format == SubtitleFormat.VTT) {
            parseVtt(content)
        } else {
            parseSrt(content)
        }

        return SubtitleFileContent(
            fileName = fileName,
            format = format,
            segments = segments
        )
    }

    private fun parseSrt(raw: String): List<SubtitleSegment> {
        val list = mutableListOf<SubtitleSegment>()
        // Normalize line breaks
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split("\n\n")

        var currentIndex = 1
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.size >= 2) {
                val timecodeLineIndex = lines.indexOfFirst { it.contains("-->") }
                if (timecodeLineIndex != -1) {
                    val timecode = lines[timecodeLineIndex].trim()
                    val dialogueLines = lines.drop(timecodeLineIndex + 1)
                    val originalText = dialogueLines.joinToString("\n").trim()
                    if (originalText.isNotEmpty()) {
                        list.add(
                            SubtitleSegment(
                                index = currentIndex++,
                                timecode = timecode,
                                originalText = originalText
                            )
                        )
                    }
                }
            }
        }
        return list
    }

    private fun parseVtt(raw: String): List<SubtitleSegment> {
        val list = mutableListOf<SubtitleSegment>()
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split("\n\n")

        var currentIndex = 1
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.none { it.startsWith("WEBVTT") || it.startsWith("NOTE") }) {
                val timecodeLineIndex = lines.indexOfFirst { it.contains("-->") }
                if (timecodeLineIndex != -1) {
                    val timecode = lines[timecodeLineIndex].trim()
                    val dialogueLines = lines.drop(timecodeLineIndex + 1)
                    val originalText = dialogueLines.joinToString("\n").trim()
                    if (originalText.isNotEmpty()) {
                        list.add(
                            SubtitleSegment(
                                index = currentIndex++,
                                timecode = timecode,
                                originalText = originalText
                            )
                        )
                    }
                }
            }
        }
        return list
    }

    fun serialize(subtitleFile: SubtitleFileContent): String {
        val sb = StringBuilder()
        if (subtitleFile.format == SubtitleFormat.VTT) {
            sb.append("WEBVTT\n\n")
            for (segment in subtitleFile.segments) {
                sb.append("${segment.index}\n")
                sb.append("${segment.timecode}\n")
                sb.append("${segment.translatedText ?: segment.originalText}\n\n")
            }
        } else {
            for (segment in subtitleFile.segments) {
                sb.append("${segment.index}\n")
                sb.append("${segment.timecode}\n")
                sb.append("${segment.translatedText ?: segment.originalText}\n\n")
            }
        }
        return sb.toString().trim()
    }
}
