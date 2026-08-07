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
                parseAndApplyBatchTranslation(currentBatchSegments, translatedBatchOutput)
            } else {
                // Retry each segment individually in case batch prompt failed
                for (seg in currentBatchSegments) {
                    val singleReq = TranslationRequest(
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        text = seg.originalText,
                        tone = tone,
                        isSubtitle = true
                    )
                    val singleResult = translationService.translate(singleReq)
                    seg.translatedText = singleResult.getOrNull()?.translatedText ?: seg.originalText
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

    private fun parseAndApplyBatchTranslation(
        segments: List<SubtitleSegment>,
        translatedOutput: String
    ) {
        val map = mutableMapOf<Int, String>()
        val lines = translatedOutput.lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                val closingBracket = trimmed.indexOf("]")
                if (closingBracket != -1) {
                    val idStr = trimmed.substring(1, closingBracket)
                    val id = idStr.toIntOrNull()
                    if (id != null) {
                        val text = trimmed.substring(closingBracket + 1).trim()
                        map[id] = text
                    }
                }
            }
        }

        for (seg in segments) {
            val translated = map[seg.index]
            if (!translated.isNullOrBlank()) {
                seg.translatedText = translated
            } else {
                // Fallback: if tag matching missed a line, use original text
                seg.translatedText = seg.originalText
            }
        }
    }
}
