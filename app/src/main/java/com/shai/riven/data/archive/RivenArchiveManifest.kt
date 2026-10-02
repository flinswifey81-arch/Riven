package com.shai.riven.data.archive

import com.shai.riven.data.persistence.model.AttachmentState
import org.json.JSONArray
import org.json.JSONObject

internal object RivenArchiveManifestJson {
    private val versionOneManifestKeys = setOf(
        "archiveFormatVersion",
        "exportedAt",
        "databaseSchemaVersion",
        "databaseSha256",
        "secretsIncluded",
        "attachments",
    )
    private val versionTwoManifestKeys = versionOneManifestKeys + setOf(
        "reminderDatabaseSchemaVersion",
        "reminderDatabaseSha256",
    )
    private val attachmentKeys = setOf(
        "attachmentId",
        "storageKey",
        "state",
        "blobPresent",
        "archivePath",
        "byteSize",
        "contentSha256",
    )

    fun encode(manifest: RivenArchiveManifest): ByteArray {
        val attachments = JSONArray()
        manifest.attachments.forEach { record ->
            attachments.put(
                JSONObject()
                    .put("attachmentId", record.attachmentId)
                    .put("storageKey", record.storageKey)
                    .put("state", record.state.name)
                    .put("blobPresent", record.blobPresent)
                    .put("archivePath", record.archivePath ?: JSONObject.NULL)
                    .put("byteSize", record.byteSize ?: JSONObject.NULL)
                    .put("contentSha256", record.contentSha256 ?: JSONObject.NULL),
            )
        }
        val root = JSONObject()
            .put("archiveFormatVersion", manifest.archiveFormatVersion)
            .put("exportedAt", manifest.exportedAt)
            .put("databaseSchemaVersion", manifest.databaseSchemaVersion)
            .put("databaseSha256", manifest.databaseSha256)
            .put("secretsIncluded", manifest.secretsIncluded)
            .put("attachments", attachments)
        if (manifest.archiveFormatVersion >= 2) {
            root.put(
                "reminderDatabaseSchemaVersion",
                manifest.reminderDatabaseSchemaVersion ?: JSONObject.NULL,
            )
            root.put(
                "reminderDatabaseSha256",
                manifest.reminderDatabaseSha256 ?: JSONObject.NULL,
            )
        }
        return root.toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): RivenArchiveManifest? = runCatching {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val archiveFormatVersion = root.strictInt("archiveFormatVersion")
        val expectedKeys = if (archiveFormatVersion == 1) {
            versionOneManifestKeys
        } else {
            versionTwoManifestKeys
        }
        require(root.keysSet() == expectedKeys)
        val array = root.get("attachments") as? JSONArray ?: error("attachments")
        val records = buildList {
            repeat(array.length()) { index ->
                val value = array.getJSONObject(index)
                require(value.keysSet() == attachmentKeys)
                add(
                    RivenArchiveAttachmentRecord(
                        attachmentId = value.strictString("attachmentId"),
                        storageKey = value.strictString("storageKey"),
                        state = AttachmentState.valueOf(value.strictString("state")),
                        blobPresent = value.strictBoolean("blobPresent"),
                        archivePath = value.optionalString("archivePath"),
                        byteSize = value.optionalLong("byteSize"),
                        contentSha256 = value.optionalString("contentSha256"),
                    ),
                )
            }
        }
        RivenArchiveManifest(
            archiveFormatVersion = archiveFormatVersion,
            exportedAt = root.strictLong("exportedAt"),
            databaseSchemaVersion = root.strictInt("databaseSchemaVersion"),
            databaseSha256 = root.strictString("databaseSha256"),
            secretsIncluded = root.strictBoolean("secretsIncluded"),
            attachments = records,
            reminderDatabaseSchemaVersion = if (archiveFormatVersion >= 2) {
                root.optionalInt("reminderDatabaseSchemaVersion")
            } else {
                null
            },
            reminderDatabaseSha256 = if (archiveFormatVersion >= 2) {
                root.optionalString("reminderDatabaseSha256")
            } else {
                null
            },
        )
    }.getOrNull()

    private fun JSONObject.optionalString(key: String): String? =
        if (isNull(key)) null else strictString(key)

    private fun JSONObject.optionalLong(key: String): Long? =
        if (isNull(key)) null else strictLong(key)

    private fun JSONObject.optionalInt(key: String): Int? =
        if (isNull(key)) null else strictInt(key)

    private fun JSONObject.strictString(key: String): String =
        (get(key) as? String) ?: error(key)

    private fun JSONObject.strictBoolean(key: String): Boolean =
        (get(key) as? Boolean) ?: error(key)

    private fun JSONObject.strictInt(key: String): Int {
        val value = strictLong(key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE)
        return value.toInt()
    }

    private fun JSONObject.strictLong(key: String): Long = when (val value = get(key)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> error(key)
    }

    private fun JSONObject.keysSet(): Set<String> = buildSet {
        val names = keys()
        while (names.hasNext()) add(names.next())
    }
}
