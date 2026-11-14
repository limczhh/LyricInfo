package com.lidesheng.lyricinfo.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/** Ordered, one-use auxiliary matching. Source loading owns this work, not serialization. */
internal object LyricLaneAligner {
    private const val MAX_DELTA_MS = 1_000L
    private const val MAX_OFFSET_MS = 3_000L
    private const val OFFSET_BIN_MS = 100L
    private const val OFFSET_CLUSTER_MS = 300L
    private const val MAX_CELLS = 1_000_000L
    private const val MATCH_REWARD = 2_000
    private val TIME = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]""")
    private val WORD_TIME = Regex("""<\d{1,3}:\d{2}(?:\.\d{1,3})?>""")
    private val OFFSET = Regex("""(?im)^\s*\[offset:\s*([+-]?\d{1,9})\s*]\s*$""")
    private val CREDIT = Regex(
        """^(?:作词|作曲|编曲|翻译|译者|词|曲|lyrics?|composer|arranger|translated by)\s*[:：]""",
        RegexOption.IGNORE_CASE
    )

    private data class Line(val timeMs: Long, val rawTimeMs: Long, val body: String, val text: String, val key: String)
    private data class Lane(val lines: List<Line>, val offset: Long)
    private data class Pairing(val primary: Int, val auxiliary: Int)
    private data class Match(val pairs: List<Pairing>, val score: Int)

    fun prepare(source: LyricResult): PreparedLyric {
        val lyric = LyricNormalizer.cleanRawLyric(source.lyric)
        val rawLyric = source.rawLyric?.let(LyricNormalizer::cleanRawLyric)
        if (source.translation.isNullOrBlank() && source.roma.isNullOrBlank()) {
            return PreparedLyric(source, lyric, rawLyric, null, null)
        }
        val primary = parse(lyric)
        val reference = source.referenceLyric?.let(LyricNormalizer::cleanRawLyric)?.let(::parse)
        // The same original-to-original mapping serves both auxiliary lanes.
        val originalMap = reference?.let { matchOriginals(primary.lines, it.lines) }.orEmpty()
        return PreparedLyric(
            source, lyric, rawLyric,
            alignLane(primary, source.translation, reference, originalMap),
            alignLane(primary, source.roma, reference, originalMap)
        )
    }

    private fun alignLane(primary: Lane, content: String?, reference: Lane?, originalMap: Map<Int, Int>): String? {
        if (content.isNullOrBlank() || primary.lines.isEmpty()) return null
        val auxiliary = parse(LyricNormalizer.cleanRawLyric(content))
        if (auxiliary.lines.isEmpty()) return null
        val referencePairs = reference?.let { matchTimes(it.lines, auxiliary.lines) }.orEmpty()
        val bridged = referencePairs.mapNotNull { pair ->
            originalMap[pair.primary]?.let { Pairing(it, pair.auxiliary) }
        }
        val claimedAuxiliary = referencePairs.mapTo(hashSetOf()) { it.auxiliary }
        val usedPrimary = bridged.mapTo(hashSetOf()) { it.primary }
        val direct = if (claimedAuxiliary.size == auxiliary.lines.size) emptyList() else {
            matchTimes(primary.lines, auxiliary.lines).filter { candidate ->
                // If a reference row has no one-to-one original, do not guess a split/merge.
                candidate.auxiliary !in claimedAuxiliary && candidate.primary !in usedPrimary &&
                    bridged.all { anchor ->
                        (candidate.primary < anchor.primary) == (candidate.auxiliary < anchor.auxiliary)
                    }
            }
        }
        val pairs = (bridged + direct).sortedBy { it.primary }
        if (pairs.isEmpty()) return null
        val rendered = pairs.map { pair ->
            val target = primary.lines[pair.primary]
            val row = auxiliary.lines[pair.auxiliary]
            // A corrected line anchor cannot establish corrected word timing.
            val body = if (row.timeMs == target.timeMs && auxiliary.offset == primary.offset) row.body else row.text
            "${timestamp(target.rawTimeMs)}$body"
        }
        return (if (primary.offset != 0L) listOf("[offset:${primary.offset}]") else emptyList())
            .plus(rendered).joinToString("\n")
    }

    /** Input is already normalized by its provider; only parse the public LRC/ELRC contract. */
    private fun parse(content: String): Lane {
        val offset = OFFSET.find(content)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = mutableListOf<Line>()
        for (raw in content.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            val tags = mutableListOf<MatchResult>()
            var cursor = 0
            while (true) {
                val tag = TIME.find(line, cursor) ?: break
                if (tag.range.first != cursor) break
                tags += tag
                cursor = tag.range.last + 1
            }
            if (tags.isEmpty()) continue
            val body = line.substring(cursor)
            val text = WORD_TIME.replace(body, "").trim()
            if (isNoise(text)) continue
            val key = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
                .filter(Char::isLetterOrDigit)
            for (tag in tags) {
                val seconds = tag.groupValues[2].toLong()
                if (seconds >= 60L) continue
                val rawTime = tag.groupValues[1].toLong() * 60_000L + seconds * 1_000L +
                    tag.groupValues[3].padEnd(3, '0').toLong()
                lines += Line(rawTime - offset, rawTime, body, text, key)
            }
        }
        return Lane(lines.sortedBy { it.timeMs }, offset)
    }

    private fun isNoise(text: String): Boolean {
        if (text.isBlank() || text.contains("本翻译作品的著作权") || CREDIT.containsMatchIn(text)) return true
        return text.filterNot(Char::isWhitespace).all { it == '/' || it == '\\' || it == '／' || it == '＼' }
    }

    private fun matchTimes(primary: List<Line>, auxiliary: List<Line>): List<Pairing> {
        if (primary.isEmpty() || auxiliary.isEmpty()) return emptyList()
        if (primary.size.toLong() * auxiliary.size > MAX_CELLS) return exactMatches(primary, auxiliary)
        // Exact anchors divide the timeline. An aligned ending must not hide ambiguity in a verse.
        val exact = exactMatches(primary, auxiliary)
        val result = mutableListOf<Pairing>()
        var primaryStart = 0
        var auxiliaryStart = 0
        for (anchor in exact + Pairing(primary.size, auxiliary.size)) {
            result += matchSegment(
                primary.subList(primaryStart, anchor.primary),
                auxiliary.subList(auxiliaryStart, anchor.auxiliary)
            ).map { Pairing(it.primary + primaryStart, it.auxiliary + auxiliaryStart) }
            if (anchor.primary < primary.size) result += anchor
            primaryStart = anchor.primary + 1
            auxiliaryStart = anchor.auxiliary + 1
        }
        return result
    }

    private fun matchSegment(primary: List<Line>, auxiliary: List<Line>): List<Pairing> {
        if (primary.isEmpty() || auxiliary.isEmpty()) return emptyList()
        val baseline = timeDp(primary, auxiliary, 0L)
        val bins = mutableMapOf<Long, MutableList<Long>>()
        for (left in primary) for (right in auxiliary) {
            val delta = right.timeMs - left.timeMs
            if (abs(delta) <= MAX_OFFSET_MS) {
                bins.getOrPut((delta.toDouble() / OFFSET_BIN_MS).roundToLong()) { mutableListOf() }.add(delta)
            }
        }
        val votes = bins.keys.map { bin -> (-1L..1L).flatMap { bins[bin + it].orEmpty() } }
            .filter { it.size >= 3 }.sortedByDescending { it.size }
        val offsets = mutableListOf(0L)
        for (vote in votes) {
            val offset = vote.sorted()[vote.size / 2]
            if (offsets.all { abs(offset - it) > OFFSET_CLUSTER_MS }) offsets += offset
        }
        val hypotheses = mutableListOf(baseline)
        for (offset in offsets.drop(1)) {
            val candidate = timeDp(primary, auxiliary, offset)
            val pairs = candidate.pairs
            if (pairs.size >= 3 && pairs.size * 10 >= auxiliary.size * 7 &&
                primary[pairs.last().primary].timeMs - primary[pairs.first().primary].timeMs >= 10_000L
            ) hypotheses += candidate
        }
        val best = hypotheses.maxBy { it.score }
        // Alternatives within the value of one row are plausible. Publish only their common pairs.
        val plausible = hypotheses.filter { best.score - it.score <= MATCH_REWARD }
        return best.pairs.filter { pair -> plausible.all { pair in it.pairs } }
    }

    private fun exactMatches(primary: List<Line>, auxiliary: List<Line>): List<Pairing> {
        val byTime = primary.indices.groupBy { primary[it].timeMs }
            .mapValues { (_, indices) -> ArrayDeque(indices) }
        return auxiliary.indices.mapNotNull { index ->
            byTime[auxiliary[index].timeMs]?.removeFirstOrNull()?.let { Pairing(it, index) }
        }
    }

    private fun timeDp(primary: List<Line>, auxiliary: List<Line>, offset: Long): Match {
        val width = auxiliary.size + 1
        val decisions = ByteArray((primary.size + 1) * width)
        var previous = IntArray(width)
        val primaryWindows = windows(primary)
        val auxiliaryWindows = windows(auxiliary)
        for (i in primary.indices) {
            val current = IntArray(width)
            for (j in auxiliary.indices) {
                val cell = (i + 1) * width + j + 1
                current[j + 1] = previous[j + 1]
                decisions[cell] = 1
                if (current[j] > current[j + 1]) {
                    current[j + 1] = current[j]
                    decisions[cell] = 2
                }
                val residual = abs(auxiliary[j].timeMs - primary[i].timeMs - offset)
                if (residual <= min(primaryWindows[i], auxiliaryWindows[j])) {
                    val score = previous[j] + MATCH_REWARD - residual.toInt()
                    if (score > current[j + 1]) {
                        current[j + 1] = score
                        decisions[cell] = 3
                    }
                }
            }
            previous = current
        }
        return Match(backtrack(decisions, primary.size, auxiliary.size), previous.last())
    }

    private fun backtrack(decisions: ByteArray, primarySize: Int, auxiliarySize: Int): List<Pairing> {
        val width = auxiliarySize + 1
        val pairs = mutableListOf<Pairing>()
        var i = primarySize
        var j = auxiliarySize
        while (i > 0 && j > 0) {
            when (decisions[i * width + j].toInt()) {
                3 -> { pairs += Pairing(i - 1, j - 1); i--; j-- }
                2 -> j--
                else -> i--
            }
        }
        return pairs.asReversed()
    }

    private fun windows(lines: List<Line>): LongArray {
        val windows = LongArray(lines.size) { MAX_DELTA_MS }
        var start = 0
        while (start < lines.size) {
            var end = start + 1
            while (end < lines.size && lines[end].timeMs == lines[start].timeMs) end++
            var gap = MAX_DELTA_MS * 3
            if (start > 0) gap = min(gap, lines[start].timeMs - lines[start - 1].timeMs)
            if (end < lines.size) gap = min(gap, lines[end].timeMs - lines[start].timeMs)
            for (index in start until end) windows[index] = min(MAX_DELTA_MS, gap * 45 / 100)
            start = end
        }
        return windows
    }

    /** Only one-to-one same-language rows establish a translation bridge. */
    private fun matchOriginals(primary: List<Line>, reference: List<Line>): Map<Int, Int> {
        if (primary.size.toLong() * reference.size > MAX_CELLS) return emptyMap()
        val width = reference.size + 1
        val decisions = ByteArray((primary.size + 1) * width)
        var previous = IntArray(width)
        for (i in primary.indices) {
            val current = IntArray(width)
            for (j in reference.indices) {
                val cell = (i + 1) * width + j + 1
                current[j + 1] = previous[j + 1]
                decisions[cell] = 1
                if (current[j] > current[j + 1]) {
                    current[j + 1] = current[j]
                    decisions[cell] = 2
                }
                if (primary[i].key.isNotEmpty() && primary[i].key == reference[j].key) {
                    val distance = abs(primary[i].timeMs - reference[j].timeMs)
                    val score = previous[j] + 1_000 - min(100L, distance / 100).toInt()
                    if (score > current[j + 1]) {
                        current[j + 1] = score
                        decisions[cell] = 3
                    }
                }
            }
            previous = current
        }
        return backtrack(decisions, primary.size, reference.size).associate { it.auxiliary to it.primary }
    }

    private fun timestamp(ms: Long): String = String.format(
        Locale.ROOT, "[%02d:%02d.%03d]", ms / 60_000L, ms / 1_000L % 60L, ms % 1_000L
    )
}
