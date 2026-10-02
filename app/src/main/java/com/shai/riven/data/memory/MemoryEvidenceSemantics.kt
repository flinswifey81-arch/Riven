package com.shai.riven.data.memory

import com.shai.riven.data.persistence.model.EvidenceRole

/**
 * Evidence that positively grounds the Memory row it belongs to.
 *
 * CORRECTS is positive for the replacement Memory created by an explicit correction. It must not
 * be confused with the corrected-false predecessor, which is fenced by that predecessor's truth
 * state. Contradicting, refining, and resolving evidence cannot independently support a claim.
 */
fun EvidenceRole.isPositiveMemoryGrounding(): Boolean =
    this == EvidenceRole.SUPPORTS || this == EvidenceRole.CORRECTS
