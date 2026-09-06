package com.example.data.subtitle

import com.example.data.model.TranslationRequest
import com.example.data.model.TranslationTone
import com.example.data.service.TranslationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Translates a whole subtitle file.
 *
 * Performance: batches are dispatched **concurrently** (bounded by [maxConcurrentRequests])
 * instead of strictly one-after-another. Subtitle files routinely contain 800–2000 cues, which
 * used to mean 100–250 sequential round trips of 2–5 s each — i.e. many minutes of waiting while
 * the network sat idle between calls. Overlapping the requests cuts the wall-clock time by
 * roughly the concurrency factor. Individual fallback retries inside a batch are overlapped too.
 */
class SubtitleTranslatorEngine(
    private val translationService: TranslationService,
    private val maxConcurrentRequests: Int = DEFAULT_CONCURRENCY
) {

    fun translateSubtitles(
        subtitleFile: SubtitleFileContent,
        sourceLanguage: String,
        targetLanguage: String,
        tone: TranslationTone = TranslationTone.AUTO,
        batchSize: Int = 8
    ): Flow<SubtitleProgress> = channelFlow {
        val segments = subtitleFile.segments
        val totalSegments = segments.size

        if (totalSegments == 0) {
            send(
                SubtitleProgress(
                    currentBatch = 0,
                    totalBatches = 0,
                    processedSegments = 0,
                    totalSegments = 0,
                    isComplete = true
                )
            )
            return@channelFlow
        }

        val batches = segments.chunked(batchSize.coerceAtLeast(1))
        val totalBatches = batches.size
        val concurrency = maxConcurrentRequests.coerceIn(1, totalBatches)

        // Progress is now reported by *completed* work rather than by position in a serial loop,
        // because several batches are in flight at once.
        var processedCount = 0
        var completedBatches = 0

        send(
            SubtitleProgress(
                currentBatch = 0,
                totalBatches = totalBatches,
                processedSegments = 0,
                totalSegments = totalSegments
            )
        )

        val gate = Semaphore(concurrency)

        coroutineScope {
            val jobs: List<Deferred<Int>> = batches.map { batchSegments ->
                async {
                    translateBatch(this, gate, batchSegments, sourceLanguage, targetLanguage, tone)
                    batchSegments.size
                }
            }

            for (job in jobs) {
                processedCount += job.await()
                completedBatches++
                send(
                    SubtitleProgress(
                        currentBatch = completedBatches,
                        totalBatches = totalBatches,
                        processedSegments = processedCount,
                        totalSegments = totalSegments
                    )
                )
            }
        }

        send(
            SubtitleProgress(
                currentBatch = totalBatches,
                totalBatches = totalBatches,
                processedSegments = totalSegments,
                totalSegments = totalSegments,
                isComplete = true
            )
        )
    }

    private suspend fun translateBatch(
        scope: CoroutineScope,
        gate: Semaphore,
        batchSegments: List<SubtitleSegment>,
        sourceLanguage: String,
        targetLanguage: String,
        tone: TranslationTone
    ) {
        // Format batch text with index tags [ID] text
        val batchText = batchSegments.joinToString("\n") { seg ->
            "[${seg.index}] ${seg.originalText.replace("\n", " ")}"
        }

        val request = TranslationRequest(
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            text = batchText,
            tone = tone,
            isSubtitle = true
        )

        // The permit is held only for the duration of the actual network call, so the
        // per-segment fallbacks below are throttled by the same global limit.
        val translationResult = gate.withPermit { translationService.translate(request) }

        val missing: List<SubtitleSegment> = if (translationResult.isSuccess) {
            val translatedBatchOutput = translationResult.getOrNull()?.translatedText ?: ""
            val matchedIds = parseAndApplyBatchTranslation(batchSegments, translatedBatchOutput)
            // Any segment the batch reply did not cover (e.g. the model dropped or mangled its
            // index tag) is retried on its own, so it is still really translated into the target
            // language instead of silently staying in the source language.
            batchSegments.filter { it.index !in matchedIds }
        } else {
            // Retry each segment individually in case the batch prompt failed.
            batchSegments
        }

        if (missing.isEmpty()) return

        // Perf: the per-segment fallbacks are independent, so run them together rather than
        // one blocking round trip after another.
        missing
            .map { seg ->
                scope.async {
                    gate.withPermit {
                        translateSegmentIndividually(seg, sourceLanguage, targetLanguage, tone)
                    }
                }
            }
            .awaitAll()
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

    companion object {
        /**
         * Number of translation requests allowed in flight at once. Kept modest so free-tier
         * provider rate limits (HTTP 429) are not tripped, while still hiding most latency.
         */
        const val DEFAULT_CONCURRENCY = 4

        // Leading index tag variants: [12], [ 12 ], 【12】, (12), with optional ":-." separators.
        private val LINE_TAG_REGEX = Regex("""^\s*[\[【(]\s*(\d+)\s*[\]】)]\s*[:\-–—.]?\s*(.*)$""")
        private val INDEX_TAG_PREFIX_REGEX = Regex("""^\s*[\[【(]\s*\d+\s*[\]】)]\s*[:\-–—.]?\s*""")
    }
}
