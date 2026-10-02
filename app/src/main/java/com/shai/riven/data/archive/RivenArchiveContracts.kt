package com.shai.riven.data.archive

import com.shai.riven.data.persistence.model.AttachmentState
import java.io.InputStream
import java.io.OutputStream

const val RIVEN_ARCHIVE_FORMAT_VERSION = 2

data class ExportRivenArchiveInput(
    val output: OutputStream,
    val exportedAt: Long,
)

data class StageRivenRestoreInput(
    val input: InputStream,
    val occurredAt: Long,
)

data class RivenArchiveLimits(
    val maximumManifestBytes: Long = 2L * 1024L * 1024L,
    val maximumDatabaseBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maximumAttachmentBytes: Long = 2L * 1024L * 1024L * 1024L,
    val maximumTotalExtractedBytes: Long = 16L * 1024L * 1024L * 1024L,
    val maximumEntryCount: Int = 100_000,
) {
    init {
        require(maximumManifestBytes > 0)
        require(maximumDatabaseBytes > 0)
        require(maximumAttachmentBytes > 0)
        require(maximumTotalExtractedBytes > 0)
        require(maximumEntryCount >= 2)
    }
}

data class RivenArchiveAttachmentRecord(
    val attachmentId: String,
    val storageKey: String,
    val state: AttachmentState,
    val blobPresent: Boolean,
    val archivePath: String?,
    val byteSize: Long?,
    val contentSha256: String?,
)

data class RivenArchiveManifest(
    val archiveFormatVersion: Int,
    val exportedAt: Long,
    val databaseSchemaVersion: Int,
    val databaseSha256: String,
    val secretsIncluded: Boolean,
    val attachments: List<RivenArchiveAttachmentRecord>,
    val reminderDatabaseSchemaVersion: Int? = null,
    val reminderDatabaseSha256: String? = null,
)

enum class ArchiveLimit {
    MANIFEST_BYTES,
    DATABASE_BYTES,
    ATTACHMENT_BYTES,
    TOTAL_EXTRACTED_BYTES,
    ENTRY_COUNT,
}

sealed interface RivenArchiveExportError {
    data class ExportSnapshotFailure(val causeType: String) : RivenArchiveExportError
    data class ExportAttachmentMissing(val attachmentId: String) : RivenArchiveExportError
    data class ExportAttachmentMismatch(val attachmentId: String) : RivenArchiveExportError
    data class ExportIoFailure(val causeType: String) : RivenArchiveExportError
}

sealed interface RivenArchiveRestoreError {
    data object UnsupportedArchiveVersion : RivenArchiveRestoreError
    data object MalformedManifest : RivenArchiveRestoreError
    data object UnsafeArchivePath : RivenArchiveRestoreError
    data object DuplicateArchiveEntry : RivenArchiveRestoreError
    data class ArchiveLimitExceeded(val limit: ArchiveLimit) : RivenArchiveRestoreError
    data class ArchiveIntegrityFailure(val component: String) : RivenArchiveRestoreError
    data class DatabaseTooNew(
        val foundVersion: Int,
        val supportedVersion: Int,
    ) : RivenArchiveRestoreError

    data class DatabaseMigrationFailure(val causeType: String) : RivenArchiveRestoreError
    data class DatabaseIntegrityFailure(val check: String) : RivenArchiveRestoreError
    data class AttachmentIntegrityFailure(val attachmentId: String) : RivenArchiveRestoreError
    data object RestoreAlreadyPending : RivenArchiveRestoreError
    data object FactoryResetPending : RivenArchiveRestoreError
    data class RestoreStagingFailure(val causeType: String) : RivenArchiveRestoreError
    data class RestoreInstallFailure(val causeType: String) : RivenArchiveRestoreError
    data class RestoreVerificationFailure(val causeType: String) : RivenArchiveRestoreError
    data class RollbackFailure(val causeType: String) : RivenArchiveRestoreError
    data class RecoveryFailure(val state: String) : RivenArchiveRestoreError
}

sealed interface ExportRivenArchiveResult {
    data class Exported(
        val archiveFormatVersion: Int,
        val databaseSchemaVersion: Int,
        val attachmentCount: Int,
        val archiveByteCount: Long,
    ) : ExportRivenArchiveResult

    data class Failure(val error: RivenArchiveExportError) : ExportRivenArchiveResult
}

sealed interface StageRivenRestoreResult {
    data class RestoreStaged(
        val archiveFormatVersion: Int,
        val sourceDatabaseVersion: Int,
        val resultingDatabaseVersion: Int,
        val attachmentCount: Int,
        val restartRequired: Boolean = true,
    ) : StageRivenRestoreResult

    data class Failure(val error: RivenArchiveRestoreError) : StageRivenRestoreResult
}

sealed interface RivenRestoreBootstrapResult {
    data object NoPendingRestore : RivenRestoreBootstrapResult
    data object RestoreApplied : RivenRestoreBootstrapResult
    data class PreviousStateRestored(
        val trigger: RivenArchiveRestoreError,
    ) : RivenRestoreBootstrapResult
    data class Failure(val error: RivenArchiveRestoreError) : RivenRestoreBootstrapResult
}

internal const val CURRENT_RIVEN_DATABASE_VERSION = 8
internal const val CURRENT_REMINDER_DATABASE_VERSION = 1
internal const val MINIMUM_SUPPORTED_RIVEN_ARCHIVE_FORMAT_VERSION = 1
internal const val ARCHIVE_MANIFEST_PATH = "manifest.json"
internal const val ARCHIVE_DATABASE_PATH = "database/riven.db"
internal const val ARCHIVE_REMINDER_DATABASE_PATH = "database/riven-reminders.db"
internal const val ARCHIVE_ATTACHMENT_PREFIX = "attachments/"
internal val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
