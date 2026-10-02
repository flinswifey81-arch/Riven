package com.shai.riven.data.memory

import com.shai.riven.data.persistence.dao.MaintenanceDao
import java.security.MessageDigest

/**
 * Canonical v1 suppression hash for one evidence lineage.
 *
 * The byte contract intentionally remains SHA-256 of `experienceId + ":" + lineageKey` so
 * existing Forget/Delete tombstones remain compatible.
 */
internal fun sourceLineageHash(experienceId: String, lineageKey: String): String {
    val canonicalLineage = "$experienceId:$lineageKey"
    return MessageDigest.getInstance("SHA-256")
        .digest(canonicalLineage.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

internal data class SourceSuppressionCoverage(
    val sourceIdentityHash: String,
    val startOffset: Int,
    val endOffsetExclusive: Int,
)

/** Opaque stable identity for an immutable source Experience; it contains no source content. */
internal fun sourceIdentityHash(experienceId: String): String = sha256(
    canonical = buildString {
        append("riven-source-identity-v1:")
        append(experienceId.toByteArray(Charsets.UTF_8).size)
        append(':')
        append(experienceId)
    },
)

internal fun sourceSuppressionCoverage(
    experienceId: String,
    lineageKey: String,
): SourceSuppressionCoverage? {
    val (anchorStart, anchorEnd) = automaticClaimOffsets(lineageKey) ?: return null
    if (anchorStart < 0 || anchorEnd <= anchorStart) return null
    return SourceSuppressionCoverage(
        sourceIdentityHash = sourceIdentityHash(experienceId),
        startOffset = anchorStart,
        endOffsetExclusive = anchorEnd,
    )
}

/**
 * Minimal claim-scoped suppression key. Automatic candidate lineage v4 embeds a server-validated
 * immutable source-anchor range plus a semantic digest. Only the nonsemantic source offsets are
 * retained in the suppression hash, so paraphrased meaning cannot evade Forget/Delete and deleted
 * semantic content is not retained. Older coarse/model-derived and manual lineages fall back to
 * their exact hash. V1-V3 automatic lineages therefore retain exact-lineage compatibility only;
 * they do not receive V4 paraphrase reconciliation because they contain no validated child range.
 */
internal fun sourceClaimSuppressionHash(experienceId: String, lineageKey: String): String {
    val (anchorStart, anchorEnd) = automaticClaimOffsets(lineageKey)
        ?: return sourceLineageHash(experienceId, lineageKey)
    val canonicalSource = buildString {
        append("riven-source-claim-v3:")
        append(experienceId.toByteArray(Charsets.UTF_8).size)
        append(':')
        append(experienceId)
        append(':')
        append(anchorStart)
        append(':')
        append(anchorEnd)
    }
    return sha256(canonicalSource)
}

/** Shared transaction-local suppression predicate for every semantic rebuild/provenance path. */
internal fun isEvidenceSuppressedInCurrentTransaction(
    maintenanceDao: MaintenanceDao,
    experienceId: String,
    lineageKey: String,
): Boolean {
    val exact = maintenanceDao.suppressionTombstone(
        sourceClaimSuppressionHash(experienceId, lineageKey),
    )?.isActive == true || maintenanceDao.suppressionTombstone(
        sourceLineageHash(experienceId, lineageKey),
    )?.isActive == true
    if (exact) return true
    return sourceSuppressionCoverage(experienceId, lineageKey)?.let { coverage ->
        maintenanceDao.activeSuppressionCoverageOverlapCount(
            coverage.sourceIdentityHash,
            coverage.startOffset,
            coverage.endOffsetExclusive,
        ) != 0
    } == true
}

private fun sha256(canonical: String): String = MessageDigest.getInstance("SHA-256")
    .digest(canonical.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private val AUTO_CANDIDATE_V4_PATTERN = Regex("AUTO_CANDIDATE_V4:(\\d+):(\\d+):[0-9a-f]{64}")

private fun automaticClaimOffsets(lineageKey: String): Pair<Int, Int>? {
    val automaticClaim = AUTO_CANDIDATE_V4_PATTERN.matchEntire(lineageKey) ?: return null
    val anchorStart = automaticClaim.groupValues[1].toIntOrNull() ?: return null
    val anchorEnd = automaticClaim.groupValues[2].toIntOrNull() ?: return null
    return anchorStart to anchorEnd
}
