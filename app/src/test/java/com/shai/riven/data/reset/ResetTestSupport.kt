package com.shai.riven.data.reset

import android.content.Context
import com.shai.riven.data.archive.RIVEN_ARCHIVE_STAGING_DIRECTORY_NAME
import com.shai.riven.data.archive.RivenRestorePaths
import com.shai.riven.data.attachment.FileAttachmentBlobStore
import com.shai.riven.data.credential.ClearProviderCredentialsResult as StoreClearResult
import com.shai.riven.data.credential.DeleteProviderCredentialResult
import com.shai.riven.data.credential.FileProviderCredentialStore
import com.shai.riven.data.credential.HasProviderCredentialResult
import com.shai.riven.data.credential.ProviderCredentialError
import com.shai.riven.data.credential.ProviderCredentialKeyResetter
import com.shai.riven.data.credential.ProviderCredentialOperation
import com.shai.riven.data.credential.ProviderCredentialStore
import com.shai.riven.data.credential.ProviderSecret
import com.shai.riven.data.credential.PutProviderCredentialResult
import com.shai.riven.data.credential.ReadProviderCredentialResult
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.ConversationDraftEntity
import com.shai.riven.data.persistence.entity.DraftAttachmentEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.ProviderProfileCapabilityEntity
import com.shai.riven.data.persistence.entity.ProviderProfileEntity
import com.shai.riven.data.persistence.entity.ShaiSystemInstructionsEntity
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.TemporalState
import com.shai.riven.data.provider.ProviderCapability
import java.io.File

internal class FakeProviderCredentialKeyResetter(
    var failuresRemaining: Int = 0,
) : ProviderCredentialKeyResetter {
    var calls = 0

    override fun deleteProviderCredentialKey() {
        calls += 1
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            error("controlled key deletion failure")
        }
    }
}

internal class FakeResetCredentialStore : ProviderCredentialStore {
    val values = mutableMapOf<String, ProviderSecret>()
    var clearCalls = 0
    var failClear = false

    override fun putCredential(
        credentialSlotId: String,
        secret: ProviderSecret,
    ): PutProviderCredentialResult {
        values[credentialSlotId] = secret
        return PutProviderCredentialResult.Success
    }

    override fun readCredential(credentialSlotId: String): ReadProviderCredentialResult =
        values[credentialSlotId]
            ?.let(ReadProviderCredentialResult::Success)
            ?: ReadProviderCredentialResult.Failure(
                ProviderCredentialError.MissingCredential(credentialSlotId),
            )

    override fun hasCredential(credentialSlotId: String): HasProviderCredentialResult =
        HasProviderCredentialResult.Success(credentialSlotId in values)

    override fun deleteCredential(credentialSlotId: String): DeleteProviderCredentialResult {
        values.remove(credentialSlotId)
        return DeleteProviderCredentialResult.Success
    }

    override fun clearAllCredentials(): StoreClearResult {
        clearCalls += 1
        if (failClear) {
            return StoreClearResult.Failure(
                ProviderCredentialError.StorageFailure(
                    ProviderCredentialOperation.CLEAR_ALL,
                    "ControlledCredentialFailure",
                ),
            )
        }
        val count = values.size
        values.clear()
        return StoreClearResult.Success(count)
    }
}

internal fun clearResetTestState(context: Context) {
    context.deleteDatabase(RivenDatabase.DATABASE_NAME)
    FileAttachmentBlobStore.rootForContext(context).deleteRecursively()
    FileProviderCredentialStore.rootForContext(context).deleteRecursively()
    File(context.noBackupFilesDir, RIVEN_ARCHIVE_STAGING_DIRECTORY_NAME).deleteRecursively()
    File(context.noBackupFilesDir, RivenRestorePaths.RESTORE_DIRECTORY).deleteRecursively()
    File(context.noBackupFilesDir, RivenResetPaths.RESET_DIRECTORY).deleteRecursively()
}

internal fun seedRepresentativeState(database: RivenDatabase) {
    database.conversationDao().insertConversation(
        ConversationEntity("conversation", 1, 1, ConversationStatus.ACTIVE, "Title"),
    )
    database.conversationDao().insertMessage(
        MessageEntity(
            id = "message",
            conversationId = "conversation",
            sequenceNumber = 1,
            role = MessageRole.USER,
            deliveryState = MessageDeliveryState.PERSISTED,
            content = "private content",
            createdAt = 1,
            updatedAt = 1,
        ),
    )
    database.memoryDao().insertExperience(
        ExperienceEntity(
            id = "experience",
            eventOrder = 1,
            experienceType = ExperienceType.CONVERSATION_MESSAGE,
            actor = ExperienceActor.SHAI,
            sourceContent = "private evidence",
            occurredAt = 1,
            recordedAt = 1,
            sensitivity = SensitivityLevel.STANDARD,
            availability = ExperienceAvailability.AVAILABLE,
        ),
    )
    database.memoryDao().insertMemory(
        MemoryEntity(
            id = "memory",
            kind = MemoryKind.SEMANTIC,
            scope = MemoryScope.SHAI,
            meaning = "private memory",
            epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
            certainty = MemoryCertainty.CERTAIN,
            truthState = MemoryTruthState.SUPPORTED,
            retentionState = MemoryRetentionState.ACTIVE,
            lifecycleState = MemoryLifecycleState.VALIDATED,
            temporalState = TemporalState.CURRENT,
            learnedAt = 1,
            sensitivity = SensitivityLevel.STANDARD,
            createdAt = 1,
            updatedAt = 1,
        ),
    )
    database.shaiSystemInstructionsDao().insert(
        ShaiSystemInstructionsEntity("instructions", "private instructions", true, 1, 1, 1),
    )
    database.providerProfileDao().insertProfile(
        ProviderProfileEntity(
            profileId = "profile",
            displayName = "Profile",
            adapterId = "adapter",
            endpointBaseUrl = "https://example.invalid",
            modelId = "model",
            credentialSlotId = "credential-slot",
            isEnabled = true,
            revision = 1,
            createdAt = 1,
            updatedAt = 1,
        ),
    )
    database.providerProfileDao().insertCapabilities(
        listOf(ProviderProfileCapabilityEntity("profile", ProviderCapability.TEXT_CHAT, 1)),
    )
    database.attachmentDao().insertAttachment(
        AttachmentEntity(
            id = "attachment",
            kind = AttachmentKind.IMAGE,
            mimeType = "image/png",
            state = AttachmentState.AVAILABLE,
            storageKey = "attachments/attachment.blob",
            byteSize = 4,
            contentSha256 = "0".repeat(64),
            source = AttachmentSource.SHAI_IMPORT,
            createdAt = 1,
            updatedAt = 1,
        ),
    )
    database.conversationDraftDao().insertDraft(
        ConversationDraftEntity(
            conversationId = "conversation",
            content = "private unsent draft",
            revision = 1,
            createdAt = 1,
            updatedAt = 1,
        ),
    )
    database.conversationDraftDao().insertDraftAttachment(
        DraftAttachmentEntity(
            conversationId = "conversation",
            attachmentId = "attachment",
            attachmentOrder = 0,
            createdAt = 1,
        ),
    )
}

internal fun applicationTableCounts(database: RivenDatabase): Map<String, Long> {
    val sqlite = database.openHelper.writableDatabase
    val tables = sqlite.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (name != "android_metadata" &&
                    name != "room_master_table" &&
                    !name.startsWith("sqlite_")
                ) {
                    add(name)
                }
            }
        }
    }
    return tables.associateWith { table ->
        sqlite.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }
}
