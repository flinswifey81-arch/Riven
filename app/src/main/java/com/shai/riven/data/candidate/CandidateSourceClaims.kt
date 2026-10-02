package com.shai.riven.data.candidate

/**
 * One immutable, server-derived claim slot in an Experience's source text. The identifier is
 * intentionally opaque to the model: its ordinal comes from deterministic source segmentation,
 * never from model output order.
 */
internal data class CandidateSourceClaim(
    val id: String,
    val text: String,
)

internal const val SOURCE_CLAIM_ID_PREFIX = "SOURCE_CLAIM_V1:"

/**
 * Produces stable source claim slots without retaining source text in lineage or tombstones.
 * Sentence boundaries, line boundaries, and comma-delimited coordinating clauses are split so
 * unrelated facts in one message remain independently forgettable.
 */
internal fun candidateSourceClaims(sourceContent: String?): List<CandidateSourceClaim> {
    if (sourceContent.isNullOrBlank()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var segmentStart = 0
    var index = 0

    fun addSegment(endExclusive: Int) {
        var start = segmentStart
        var end = endExclusive
        while (start < end && sourceContent[start].isWhitespace()) start += 1
        while (end > start && sourceContent[end - 1].isWhitespace()) end -= 1
        if (start < end) ranges += start until end
    }

    while (index < sourceContent.length) {
        val character = sourceContent[index]
        when {
            character == '\r' || character == '\n' -> {
                addSegment(index)
                if (character == '\r' && sourceContent.getOrNull(index + 1) == '\n') index += 1
                segmentStart = index + 1
            }
            character in SOURCE_SENTENCE_TERMINATORS &&
                (index == sourceContent.lastIndex || sourceContent[index + 1].isWhitespace()) -> {
                addSegment(index + 1)
                segmentStart = index + 1
            }
            character == ',' && startsCoordinatingClause(sourceContent, index + 1) -> {
                addSegment(index)
                segmentStart = index + 1
            }
        }
        index += 1
    }
    addSegment(sourceContent.length)

    return ranges.mapIndexed { ordinal, range ->
        CandidateSourceClaim(
            id = "$SOURCE_CLAIM_ID_PREFIX$ordinal",
            text = sourceContent.substring(range.first, range.last + 1),
        )
    }
}

internal fun sourceClaimOrdinal(sourceClaimId: String): Int? = SOURCE_CLAIM_ID_PATTERN
    .matchEntire(sourceClaimId)
    ?.groupValues
    ?.get(1)
    ?.toIntOrNull()

private fun startsCoordinatingClause(source: String, fromIndex: Int): Boolean {
    var index = fromIndex
    while (index < source.length && source[index].isWhitespace()) index += 1
    val wordStart = index
    while (index < source.length && source[index].isLetter()) index += 1
    if (wordStart == index || index >= source.length || !source[index].isWhitespace()) return false
    return source.substring(wordStart, index).lowercase() in COORDINATING_CONJUNCTIONS
}

private val SOURCE_SENTENCE_TERMINATORS = setOf('.', '!', '?')
private val COORDINATING_CONJUNCTIONS = setOf("and", "but", "or", "yet", "so")
private val SOURCE_CLAIM_ID_PATTERN = Regex("SOURCE_CLAIM_V1:(0|[1-9]\\d*)")
