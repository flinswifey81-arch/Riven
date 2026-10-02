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
 * Minimal claim-scoped suppression key. Automatic candidate lineage v3 embeds a server-validated
 * immutable source-claim ordinal plus a semantic digest. Only the source claim is retained in the
 * suppression hash, so a paraphrase cannot evade Forget/Delete and deleted semantic content is not
 * retained. Older positional/model-derived and manual lineages fall back to their exact hash.
 */
internal fun sourceClaimSuppressionHash(experienceId: String, lineageKey: String): String {
    val automaticClaimOrdinal = AUTO_CANDIDATE_V3_PATTERN.matchEntire(lineageKey)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
    if (automaticClaimOrdinal == null) return sourceLineageHash(experienceId, lineageKey)
    val canonicalSource = buildString {
        append("riven-source-claim-v2:")
        append(experienceId.toByteArray(Charsets.UTF_8).size)
        append(':')
        append(experienceId)
        append(':')
        append(automaticClaimOrdinal)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonicalSource.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private val AUTO_CANDIDATE_V3_PATTERN = Regex("AUTO_CANDIDATE_V3:(\\d+):[0-9a-f]{64}")
