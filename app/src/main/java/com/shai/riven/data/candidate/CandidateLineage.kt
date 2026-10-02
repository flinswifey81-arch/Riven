package com.shai.riven.data.candidate

import java.security.MessageDigest

internal const val AUTO_CANDIDATE_LINEAGE_PREFIX = "AUTO_CANDIDATE_V3:"

internal fun candidateClaimLineageKey(
    experienceId: String,
    proposal: CandidateMemoryProposal,
): String {
    val sourceClaimOrdinal = requireNotNull(sourceClaimOrdinal(proposal.sourceClaimId))
    val canonical = listOf(
        experienceId,
        proposal.proposedKind.name,
        proposal.proposedScope.name,
        proposal.proposedEpistemicBasis.name,
        proposal.proposedMeaning,
    ).joinToString(separator = "") { component ->
        val byteLength = component.toByteArray(Charsets.UTF_8).size
        "$byteLength:$component"
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    return "$AUTO_CANDIDATE_LINEAGE_PREFIX$sourceClaimOrdinal:$digest"
}

internal fun candidateSemanticClaimKey(
    experienceId: String,
    proposal: CandidateMemoryProposal,
): String {
    val canonical = listOf(
        experienceId,
        proposal.proposedKind.name,
        proposal.proposedScope.name,
        proposal.proposedEpistemicBasis.name,
        proposal.proposedMeaning,
    ).joinToString(separator = "") { component ->
        val byteLength = component.toByteArray(Charsets.UTF_8).size
        "$byteLength:$component"
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
