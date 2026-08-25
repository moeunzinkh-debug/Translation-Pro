package com.example.data.subtitle

import com.example.data.model.TranslationRequest
import com.example.data.model.TranslationTone
import com.example.data.service.TranslationService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class SubtitleTranslatorEngine(
    private val translationService: TranslationService
) {

    fun translateSubtitles(
        subtitleFile: SubtitleFileContent,
        sourceLanguage: String,
        targetLanguage: String,
        tone: TranslationTone = TranslationTone.AUTO,
        batchSize: Int = 8
    ): Flow<SubtitleProgress> = flow {
        val segments = subtitleFile.segments
        val totalSegments = segments.size
        val batches = segments.chunked(batchSize)
        val totalBatches = batches.size

        if (totalSegments == 0) {
            emit(
                SubtitleProgress(
                    currentBatch = 0,
                    totalBatches = 0,
                    processedSegments = 0,
                    totalSegments = 0,
                    isComplete = true
                )
            )
            return@flow
        }

        var processedCount = 0

        for (batchIndex in batches.indices) {
            val currentBatchSegments = batches[batchIndex]

            // Emit current progress before batch request
            emit(
                SubtitleProgress(
                    currentBatch = batchIndex + 1,
                    totalBatches = totalBatches,
                    processedSegments = processedCount,
                    totalSegments = totalSegments
                )
            )

            // Format batch text with index tags [ID] text
            val batchText = currentBatchSegments.joinToString("\n") { seg ->
                "[${seg.index}] ${seg.originalText.replace("\n", " ")}"
            }

            val request = TranslationRequest(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                text = batchText,
                tone = tone,
                isSubtitle = true
            )

            val translationResult = translationService.translate(request)

            if (translationResult.isSuccess) {
                val translatedBatchOutput = translationResult.getOrNull()?.translatedText ?: ""
                val matchedIds = parseAndApplyBatchTranslation(currentBatchSegments, translatedBatchOutput)

                // Any segment the batch reply did not cover (e.g. the model dropped or mangled
                // its index tag) is retried on its own, so it is still really translated into
                // the target language instead of silently staying in the source language.
                for (seg in currentBatchSegments) {
                    if (seg.index !in matchedIds) {
                        translateSegmentIndividually(seg, sourceLanguage, targetLanguage, tone)
                    }
                }
            } else {
                // Retry each segment individually in case batch prompt failed
                for (seg in currentBatchSegments) {
                    translateSegmentIndividually(seg, sourceLanguage, targetLanguage, tone)
                }
            }

            processedCount += currentBatchSegments.size
            emit(
                SubtitleProgress(
                    currentBatch = batchIndex + 1,
                    totalBatches = totalBatches,
                    processedSegments = processedCount,
                    totalSegments = totalSegments
                )
            )
        }

        emit(
            SubtitleProgress(
                currentBatch = totalBatches,
                totalBatches = totalBatches,
                processedSegments = totalSegments,
                totalSegments = totalSegments,
                isComplete = true
            )
        )
    }

    /**
     * Translates a single segment. The request text is index-tagged (matching the prompt's
     * tag-preservation contract) and the echoed tag is stripped from the reply.
     * Falls back to the original text only if the request itself fails.
     */
    private suspend fun translateSegmentIndividually(
        seg: SubtitleSegment,
        sourceLanguage: String,
        targetLanguage: String,
        tone: TranslationTone
    ) {
        val singleReq = TranslationRequest(
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            text = "[${seg.index}] ${seg.originalText.replace("\n", " ")}",
            tone = tone,
            isSubtitle = true
        )
        val singleResult = translationService.translate(singleReq)
        seg.translatedText = singleResult.getOrNull()?.translatedText
            ?.let { stripIndexTag(it) }
            ?.takeUnless { it.isBlank() }
            ?: seg.originalText
    }

    /** Removes a leading echo of the index tag the model was asked to preserve, e.g. "[4] x" -> "x". */
    private fun stripIndexTag(text: String): String {
        return text.replaceFirst(INDEX_TAG_PREFIX_REGEX, "").trim()
    }

    /**
     * Maps translated AI output lines back onto [segments] via their index tags and returns the
     * indices of the segments that received a translation (so the caller can retry the rest).
     *
     * Safety net: if NO tags are found at all (e.g. the model dropped every tag) but the reply
     * still has exactly one non-blank line per segment, lines are paired positionally.
     */
    internal fun parseAndApplyBatchTranslation(
        segments: List<SubtitleSegment>,
        translatedOutput: String
    ): Set<Int> {
        val validIds = segments.mapTo(HashSet()) { it.index }
        val map = mutableMapOf<Int, String>()

        for (line in translatedOutput.lines()) {
            val match = LINE_TAG_REGEX.find(line) ?: continue
            val id = match.groupValues[1].toIntOrNull() ?: continue
            // Ignore bracketed numbers inside dialogue that are not indices of this batch.
            if (id !in validIds) continue
            val text = match.groupValues[2].trim()
            if (text.isNotEmpty()) {
                map[id] = text
            }
        }

        // Positional fallback: recover the whole batch when the model returned clean translated
        // lines without tags (1 output line per input segment, same order).
        if (map.isEmpty()) {
            val candidateLines = translatedOutput.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (candidateLines.size == segments.size && segments.isNotEmpty()) {
                segments.forEachIndexed { position, seg ->
                    map[seg.index] = candidateLines[position]
                }
            }
        }

        for (seg in segments) {
            val translated = map[seg.index]
            if (!translated.isNullOrBlank()) {
                seg.translatedText = translated
            }
        }

        return map.keys
    }

    private companion object {
        // Leading index tag variants: [12], [ 12 ], 【12】, (12), with optional ":-." separators.
        val LINE_TAG_REGEX = Regex("""^\s*[\[【(]\s*(\d+)\s*[\]】)]\s*[:\-–—.]?\s*(.*)$""")
        val INDEX_TAG_PREFIX_REGEX = Regex("""^\s*[\[【(]\s*\d+\s*[\]】)]\s*[:\-–—.]?\s*""")
    }
}
