package com.example.data.subtitle

import com.example.data.model.TranslationRequest
import com.example.data.model.TranslationResult
import com.example.data.model.TranslationTone
import com.example.data.service.TranslationService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Translates subtitle dialogue in batches while keeping each translated line attached to the
 * correct subtitle segment. The segment tags are deliberately kept in the model response because
 * a batch response is otherwise impossible to map reliably back to the source subtitles.
 */
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
        val safeBatchSize = batchSize.coerceAtLeast(1)
        val batches = segments.chunked(safeBatchSize)
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

        // A second run can use a different target language. Do not leave the previous result on
        // screen while the new translation is being generated.
        segments.forEach { it.translatedText = null }

        var processedCount = 0
        var failedSegments = 0

        for (batchIndex in batches.indices) {
            val currentBatchSegments = batches[batchIndex]

            emit(
                SubtitleProgress(
                    currentBatch = batchIndex + 1,
                    totalBatches = totalBatches,
                    processedSegments = processedCount,
                    totalSegments = totalSegments
                )
            )

            // The [ID] prefix is part of the translation protocol. It must not be translated or
            // removed because it lets us put each response back into the right subtitle segment.
            val batchText = currentBatchSegments.joinToString("\n") { segment ->
                "[${segment.index}] ${segment.originalText.replace("\n", " ").trim()}"
            }

            val request = TranslationRequest(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                text = batchText,
                tone = tone,
                isSubtitle = true
            )

            val translatedIds = try {
                val translationResult = translationService.translate(request)
                if (translationResult.isSuccess) {
                    val translatedBatchOutput = translationResult.getOrNull()?.translatedText.orEmpty()
                    parseAndApplyBatchTranslation(currentBatchSegments, translatedBatchOutput)
                } else {
                    emptySet()
                }
            } catch (_: Exception) {
                // Let the per-segment fallback below make a best effort and report any items that
                // still cannot be translated in the final progress state.
                emptySet()
            }

            // Some providers occasionally ignore the [ID] format even when instructed not to.
            // Retry only the segments that could not be mapped instead of silently displaying the
            // source text as if it had been translated.
            currentBatchSegments
                .filterNot { it.index in translatedIds }
                .forEach { segment ->
                    val translated = translateSingleSegment(
                        segment = segment,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        tone = tone
                    )
                    if (!translated) failedSegments++
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
                isComplete = true,
                error = if (failedSegments > 0) {
                    "$failedSegments subtitle segment(s) could not be translated and were kept in the original language."
                } else {
                    null
                }
            )
        )
    }

    /**
     * Applies a batch response and returns the IDs that were successfully mapped.
     *
     * The preferred format is one or more lines beginning with `[ID]`. A sequential fallback is
     * also supported for providers that return one plain translated line per input segment.
     */
    private fun parseAndApplyBatchTranslation(
        segments: List<SubtitleSegment>,
        translatedOutput: String
    ): Set<Int> {
        val expectedIds = segments.map { it.index }.toSet()
        val taggedTranslations = parseTaggedTranslations(translatedOutput, expectedIds)
        val translations = if (taggedTranslations.isNotEmpty()) {
            taggedTranslations
        } else {
            parseSequentialTranslations(segments, translatedOutput)
        }

        val appliedIds = mutableSetOf<Int>()
        for (segment in segments) {
            val translated = translations[segment.index]?.trim()
            if (!translated.isNullOrBlank()) {
                segment.translatedText = translated
                appliedIds += segment.index
            }
        }
        return appliedIds
    }

    /**
     * Handles a provider response such as:
     *
     * [12] Translated first line
     * [13] Translated second line
     *
     * Continuation lines are attached to the preceding tagged segment so multiline dialogue does
     * not get lost if a provider ignores the one-line response instruction.
     */
    private fun parseTaggedTranslations(
        translatedOutput: String,
        expectedIds: Set<Int>
    ): Map<Int, String> {
        val map = linkedMapOf<Int, String>()
        var currentId: Int? = null
        val tagPattern = Regex("""^\s*\[(\d+)\]\s*(.*)$""")

        translatedOutput
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lineSequence()
            .forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line == "```") return@forEach

                val match = tagPattern.matchEntire(line)
                if (match != null) {
                    val id = match.groupValues[1].toIntOrNull()
                    if (id != null && id in expectedIds) {
                        map[id] = match.groupValues[2].trim()
                        currentId = id
                    } else {
                        currentId = null
                    }
                } else {
                    val id = currentId ?: return@forEach
                    val previous = map[id].orEmpty()
                    map[id] = listOf(previous, line)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                }
            }

        return map
    }

    /**
     * Last-resort parser for a response with no tags. This is safe only when the number of
     * non-empty output lines matches the number of input segments, or when the batch has one
     * segment (where the whole response belongs to that segment).
     */
    private fun parseSequentialTranslations(
        segments: List<SubtitleSegment>,
        translatedOutput: String
    ): Map<Int, String> {
        val lines = translatedOutput
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "```" }
            .toList()

        if (lines.isEmpty()) return emptyMap()

        return when {
            segments.size == 1 -> mapOf(segments.single().index to lines.joinToString("\n"))
            lines.size == segments.size -> segments
                .mapIndexed { index, segment -> segment.index to lines[index] }
                .toMap()
            else -> emptyMap()
        }
    }

    /** Retries one malformed or missing batch item using the same tagged response protocol. */
    private suspend fun translateSingleSegment(
        segment: SubtitleSegment,
        sourceLanguage: String,
        targetLanguage: String,
        tone: TranslationTone
    ): Boolean {
        val singleRequest = TranslationRequest(
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            // Keep the tag here too: the subtitle prompt always describes the tagged format.
            text = "[${segment.index}] ${segment.originalText.replace("\n", " ").trim()}",
            tone = tone,
            isSubtitle = true
        )

        val result = try {
            translationService.translate(singleRequest)
        } catch (_: Exception) {
            Result.failure<TranslationResult>(Exception("Single subtitle translation failed"))
        }

        if (result.isSuccess) {
            val output = result.getOrNull()?.translatedText.orEmpty()
            val tagged = parseTaggedTranslations(output, setOf(segment.index))[segment.index]
            val translated = tagged?.trim()
                ?: parseSequentialTranslations(listOf(segment), output)[segment.index]?.trim()
            if (!translated.isNullOrBlank()) {
                segment.translatedText = translated
                return true
            }
        }

        // Keep the source text as the explicit last resort so export never loses subtitle timing
        // or produces a blank dialogue line.
        segment.translatedText = segment.originalText
        return false
    }
}
