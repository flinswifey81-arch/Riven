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
