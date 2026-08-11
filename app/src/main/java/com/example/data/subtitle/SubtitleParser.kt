package com.example.data.subtitle

object SubtitleParser {

    fun parse(fileName: String, content: String): SubtitleFileContent {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val format = if (
            extension == "vtt" || content.removePrefix("\uFEFF").trimStart()
                .startsWith("WEBVTT", ignoreCase = true)
        ) {
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
        val normalized = normalizeLineBreaks(raw)
        val blocks = normalized.split(Regex("\\n{2,}"))
        var nextGeneratedIndex = 1

        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            val timecodeLineIndex = lines.indexOfFirst { it.contains("-->") }
            if (timecodeLineIndex == -1) continue

            val identifier = lines
                .take(timecodeLineIndex)
                .lastOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val parsedIndex = identifier?.toIntOrNull()
            val index = parsedIndex ?: nextAvailableIndex(nextGeneratedIndex, list)
            nextGeneratedIndex = maxOf(nextGeneratedIndex, index + 1)

            val originalText = lines
                .drop(timecodeLineIndex + 1)
                .joinToString("\n")
                .trim()
            if (originalText.isEmpty()) continue

            list += SubtitleSegment(
                index = index,
                timecode = lines[timecodeLineIndex].trim(),
                originalText = originalText,
                cueIdentifier = identifier
            )
        }
        return list
    }

    private fun parseVtt(raw: String): List<SubtitleSegment> {
        val list = mutableListOf<SubtitleSegment>()
        val normalized = normalizeLineBreaks(raw)
        val blocks = normalized.split(Regex("\\n{2,}"))
        var nextGeneratedIndex = 1

        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.isEmpty() || lines.first().startsWith("WEBVTT", ignoreCase = true)) continue
            if (lines.first().startsWith("NOTE", ignoreCase = true)) continue

            val timecodeLineIndex = lines.indexOfFirst { it.contains("-->") }
            if (timecodeLineIndex == -1) continue

            val identifier = lines
                .take(timecodeLineIndex)
                .lastOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val parsedIndex = identifier?.toIntOrNull()
            val index = parsedIndex ?: nextAvailableIndex(nextGeneratedIndex, list)
            nextGeneratedIndex = maxOf(nextGeneratedIndex, index + 1)

            val originalText = lines
                .drop(timecodeLineIndex + 1)
                .joinToString("\n")
                .trim()
            if (originalText.isEmpty()) continue

            list += SubtitleSegment(
                index = index,
                timecode = lines[timecodeLineIndex].trim(),
                originalText = originalText,
                cueIdentifier = identifier
            )
        }
        return list
    }

    fun serialize(subtitleFile: SubtitleFileContent): String {
        val sb = StringBuilder()
        if (subtitleFile.format == SubtitleFormat.VTT) {
            sb.append("WEBVTT\n\n")
            for (segment in subtitleFile.segments) {
                segment.cueIdentifier?.let { sb.append("$it\n") }
                sb.append("${segment.timecode}\n")
                sb.append("${segment.translatedText ?: segment.originalText}\n\n")
            }
        } else {
            for (segment in subtitleFile.segments) {
                sb.append("${segment.cueIdentifier ?: segment.index}\n")
                sb.append("${segment.timecode}\n")
                sb.append("${segment.translatedText ?: segment.originalText}\n\n")
            }
        }
        return sb.toString().trim()
    }

    private fun normalizeLineBreaks(raw: String): String {
        return raw.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
    }

    private fun nextAvailableIndex(
        start: Int,
        segments: List<SubtitleSegment>
    ): Int {
        val used = segments.map { it.index }.toHashSet()
        var candidate = start
        while (candidate in used) candidate++
        return candidate
    }
}
