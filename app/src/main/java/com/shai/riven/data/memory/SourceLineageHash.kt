package com.shai.riven.data.memory

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

/**
 * Minimal claim-scoped suppression key. Automatic candidate lineage v4 embeds a server-validated
 * immutable source-anchor range plus a semantic digest. Only the nonsemantic source offsets are
 * retained in the suppression hash, so paraphrased meaning cannot evade Forget/Delete and deleted
 * semantic content is not retained. Older coarse/model-derived and manual lineages fall back to
 * their exact hash. V1-V3 automatic lineages therefore retain exact-lineage compatibility only;
 * they do not receive V4 paraphrase reconciliation because they contain no validated child range.
 */
internal fun sourceClaimSuppressionHash(experienceId: String, lineageKey: String): String {
    val automaticClaim = AUTO_CANDIDATE_V4_PATTERN.matchEntire(lineageKey)
        ?: return sourceLineageHash(experienceId, lineageKey)
    val anchorStart = automaticClaim.groupValues[1].toIntOrNull()
        ?: return sourceLineageHash(experienceId, lineageKey)
    val anchorEnd = automaticClaim.groupValues[2].toIntOrNull()
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
    return MessageDigest.getInstance("SHA-256")
        .digest(canonicalSource.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private val AUTO_CANDIDATE_V4_PATTERN = Regex("AUTO_CANDIDATE_V4:(\\d+):(\\d+):[0-9a-f]{64}")
