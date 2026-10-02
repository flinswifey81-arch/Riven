package com.shai.riven.data.background

import androidx.room.withTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactPayloadEntity
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.RepairJobType
import com.shai.riven.data.validation.validationRecallCorpusFence
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** Rebuilds deterministic projections exclusively from current canonical source rows. */
class DerivedArtifactRepairService(
    private val database: RivenDatabase,
    private val clock: RivenBackgroundClock = SystemRivenBackgroundClock,
) {
    private val lifecycleDao = database.memoryLifecycleDao()
    private val maintenanceDao = database.maintenanceDao()
    private val memoryDao = database.memoryDao()
    private val recallFence = database.validationRecallCorpusFence()

    suspend fun repair(targetType: String, targetId: String): RepairJobHandlerResult {
        val artifactIds = try {
            when (targetType) {
                TARGET_ARTIFACT -> listOf(targetId)
                TARGET_MEMORY -> lifecycleDao.artifactIdsForMemory(targetId)
                TARGET_EXPERIENCE -> lifecycleDao.artifactIdsForExperience(targetId)
                TARGET_MESSAGE -> lifecycleDao.artifactIdsForMessage(targetId)
                TARGET_OPEN_LOOP -> lifecycleDao.artifactIdsForOpenLoop(targetId)
                TARGET_ATTACHMENT -> lifecycleDao.artifactIdsForAttachment(targetId)
                TARGET_CANDIDATE -> emptyList()
                else -> return RepairJobHandlerResult.PermanentFailure("UNKNOWN_REPAIR_TARGET")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return RepairJobHandlerResult.RetryableFailure("REPAIR_LOOKUP_${failure::class.java.simpleName}")
        }
        for (artifactId in artifactIds.distinct().sorted()) {
            when (val result = rebuild(artifactId)) {
                RepairJobHandlerResult.Success -> Unit
                else -> return result
            }
        }
        return RepairJobHandlerResult.Success
    }

    private suspend fun rebuild(artifactId: String): RepairJobHandlerResult {
        val generation = recallFence.snapshot()
        return try {
            recallFence.withStableGeneration(generation) {
                database.withTransaction {
                    val artifact = maintenanceDao.derivedArtifact(artifactId)
                        ?: return@withTransaction RepairJobHandlerResult.Success
                    val projection = buildProjection(artifact)
                        ?: return@withTransaction RepairJobHandlerResult.PermanentFailure(
                            "NO_CURRENT_VALID_SOURCES",
                        )
                    val hash = sha256(projection)
                    val existing = lifecycleDao.derivedPayload(artifactId)
                    if (artifact.state == DerivedArtifactState.CURRENT &&
                        artifact.artifactHash == hash &&
                        existing?.content == projection
                    ) {
                        return@withTransaction RepairJobHandlerResult.Success
                    }
                    val now = clock.now()
                    lifecycleDao.upsertDerivedPayload(
                        DerivedArtifactPayloadEntity(
                            derivedArtifactId = artifactId,
                            formatVersion = PAYLOAD_FORMAT_VERSION,
                            content = projection,
                            builtAt = now,
                        ),
                    )
                    check(
                        lifecycleDao.finishDerivedRebuild(
                            artifactId = artifactId,
                            state = DerivedArtifactState.CURRENT,
                            sourceRevision = artifact.sourceRevision + 1L,
                            artifactHash = hash,
                        ) == 1,
                    )
                    RepairJobHandlerResult.Success
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RepairJobHandlerResult.RetryableFailure(
                "REBUILD_${failure::class.java.simpleName.safeCodeFragment()}",
            )
        }
    }

    private fun buildProjection(artifact: DerivedArtifactEntity): String? {
        val memoryIds = lifecycleDao.artifactMemoryDependencies(artifact.id).map { it.memoryId }
        val experienceIds = lifecycleDao.artifactExperienceDependencies(artifact.id).map { it.experienceId }
        val messageIds = lifecycleDao.artifactMessageDependencies(artifact.id).map { it.messageId }
        val openLoopIds = lifecycleDao.artifactOpenLoopDependencies(artifact.id).map { it.openLoopId }
        val attachmentIds = lifecycleDao.artifactAttachmentDependencies(artifact.id).map { it.attachmentId }
        if (memoryIds.isEmpty() && experienceIds.isEmpty() && messageIds.isEmpty() &&
            openLoopIds.isEmpty() && attachmentIds.isEmpty()
        ) return null

        val memories = memoryIds.map { id ->
            val memory = memoryDao.memory(id) ?: return null
            if (memory.truthState != MemoryTruthState.SUPPORTED ||
                memory.retentionState == MemoryRetentionState.FORGOTTEN ||
                memory.lifecycleState != MemoryLifecycleState.VALIDATED ||
                lifecycleDao.availableEvidence(listOf(id), 1).isEmpty()
            ) return null
            memory
        }
        val experiences = experienceIds.map { id ->
            memoryDao.experience(id)?.takeIf { it.availability == ExperienceAvailability.AVAILABLE }
                ?: return null
        }
        val messages = messageIds.map { lifecycleDao.eligibleArtifactMessage(it) ?: return null }
        val loops = openLoopIds.map { lifecycleDao.openLoop(it) ?: return null }
        val attachments = attachmentIds.map { lifecycleDao.eligibleArtifactAttachment(it) ?: return null }

        return buildString {
            append("format=").append(PAYLOAD_FORMAT_VERSION)
            append("\ntype=").append(artifact.artifactType.name)
            memories.sortedBy { it.id }.forEach {
                append("\nmemory|").append(it.id).append('|').append(it.updatedAt).append('|')
                    .append(escaped(it.meaning))
            }
            experiences.sortedBy { it.id }.forEach {
                append("\nexperience|").append(it.id).append('|').append(it.occurredAt).append('|')
                    .append(escaped(it.sourceContent.orEmpty()))
            }
            messages.sortedBy { it.id }.forEach {
                append("\nmessage|").append(it.id).append('|').append(it.updatedAt).append('|')
                    .append(it.role.name).append('|').append(escaped(it.content))
            }
            loops.sortedBy { it.id }.forEach {
                append("\nopen-loop|").append(it.id).append('|').append(it.updatedAt).append('|')
                    .append(it.state.name).append('|').append(escaped(it.title)).append('|')
                    .append(escaped(it.description.orEmpty()))
            }
            attachments.sortedBy { it.id }.forEach {
                append("\nattachment|").append(it.id).append('|').append(it.updatedAt).append('|')
                    .append(it.contentSha256.orEmpty()).append('|').append(it.mimeType)
            }
        }
    }

    private fun escaped(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("|", "\\|")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun String.safeCodeFragment(): String =
        filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "Exception" }.take(40)

    companion object {
        const val PAYLOAD_FORMAT_VERSION = 1
        const val TARGET_ARTIFACT = "DERIVED_ARTIFACT"
        const val TARGET_MEMORY = "MEMORY"
        const val TARGET_EXPERIENCE = "EXPERIENCE"
        const val TARGET_MESSAGE = "MESSAGE"
        const val TARGET_OPEN_LOOP = "OPEN_LOOP"
        const val TARGET_ATTACHMENT = "ATTACHMENT"
        const val TARGET_CANDIDATE = "CANDIDATE_MEMORY"
    }
}

class DerivedArtifactRepairHandler(
    override val supportedType: RepairJobType,
    private val service: DerivedArtifactRepairService,
) : RepairJobHandler {
    override suspend fun execute(targetType: String, targetId: String): RepairJobHandlerResult =
        service.repair(targetType, targetId)
}

fun derivedArtifactRepairHandlers(
    service: DerivedArtifactRepairService,
): List<RepairJobHandler> = RepairJobType.entries.map { type ->
    DerivedArtifactRepairHandler(type, service)
}
