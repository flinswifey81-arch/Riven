package com.shai.riven.data.background

import androidx.room.withTransaction
import com.shai.riven.data.memory.isEvidenceSuppressedInCurrentTransaction
import com.shai.riven.data.memory.hasTerminalLifecycleIntent
import com.shai.riven.data.memory.isPositiveMemoryGrounding
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactPayloadEntity
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.DerivedArtifactType
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.RepairJobType
import com.shai.riven.data.validation.validationRecallCorpusFence
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * Rebuilds the one materialization with a truthful local producer: SEARCH_DOCUMENT. Conversational
 * recall currently reads canonical Memory rows directly and does not consume this payload. SUMMARY,
 * EMBEDDING, INDEX, and CACHE still require real producer/consumer integrations and fail explicitly
 * instead of being marked current with a generic raw-source projection.
 */
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
            artifactIdsForTarget(targetType, targetId)
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

    private fun artifactIdsForTarget(targetType: String, targetId: String): List<String> = when (targetType) {
        TARGET_ARTIFACT -> listOf(targetId)
        TARGET_MEMORY -> artifactIdsForMemoryLineage(targetId)
        TARGET_EXPERIENCE -> artifactIdsForExperienceLineage(targetId)
        TARGET_MESSAGE -> artifactIdsForMessageLineage(targetId)
        TARGET_OPEN_LOOP -> lifecycleDao.artifactIdsForOpenLoop(targetId)
        TARGET_ATTACHMENT -> lifecycleDao.artifactIdsForAttachment(targetId)
        TARGET_CANDIDATE -> emptyList()
        else -> error("Unknown repair target")
    }

    private fun artifactIdsForMemoryLineage(memoryId: String): List<String> {
        val experienceIds = memoryDao.evidenceForMemory(memoryId).map { it.experienceId }
        val messageIds = if (experienceIds.isEmpty()) emptyList()
        else memoryDao.messageIdsForExperiences(experienceIds)
        val openLoopIds = memoryDao.openLoopIdsForMemories(listOf(memoryId))
        return buildList {
            addAll(lifecycleDao.artifactIdsForMemory(memoryId))
            experienceIds.forEach { addAll(lifecycleDao.artifactIdsForExperience(it)) }
            messageIds.forEach { addAll(lifecycleDao.artifactIdsForMessage(it)) }
            openLoopIds.forEach { addAll(lifecycleDao.artifactIdsForOpenLoop(it)) }
        }
    }

    private fun artifactIdsForExperienceLineage(experienceId: String): List<String> = buildList {
        addAll(lifecycleDao.artifactIdsForExperience(experienceId))
        memoryDao.memoryEvidenceForExperience(experienceId).forEach { evidence ->
            addAll(lifecycleDao.artifactIdsForMemory(evidence.memoryId))
        }
        memoryDao.messageSourcesForExperience(experienceId).forEach { source ->
            addAll(lifecycleDao.artifactIdsForMessage(source.messageId))
        }
    }

    private fun artifactIdsForMessageLineage(messageId: String): List<String> = buildList {
        addAll(lifecycleDao.artifactIdsForMessage(messageId))
        memoryDao.canonicalConversationExperiencesForMessage(messageId).forEach { experience ->
            addAll(artifactIdsForExperienceLineage(experience.id))
        }
    }

    private suspend fun rebuild(artifactId: String): RepairJobHandlerResult {
        val generation = recallFence.snapshot()
        return try {
            recallFence.withStableGeneration(generation) {
                database.withTransaction {
                    val artifact = maintenanceDao.derivedArtifact(artifactId)
                        ?: return@withTransaction RepairJobHandlerResult.Success
                    if (artifact.artifactType != DerivedArtifactType.SEARCH_DOCUMENT) {
                        return@withTransaction RepairJobHandlerResult.PermanentFailure(
                            "UNSUPPORTED_ARTIFACT_TYPE_${artifact.artifactType.name}",
                        )
                    }
                    if (hasPendingProvenance(artifact)) {
                        return@withTransaction RepairJobHandlerResult.RetryableFailure(
                            "WAITING_FOR_PROVENANCE",
                        )
                    }
                    val projection = buildSearchDocument(artifact)
                        ?: return@withTransaction RepairJobHandlerResult.PermanentFailure(
                            "NO_CURRENT_VALID_SOURCES",
                        )
                    val hash = sha256(projection)
                    val existing = lifecycleDao.derivedPayload(artifactId)
                    if (artifact.state == DerivedArtifactState.CURRENT &&
                        artifact.artifactHash == hash &&
                        existing?.content == projection
                    ) return@withTransaction RepairJobHandlerResult.Success

                    val now = clock.now()
                    lifecycleDao.upsertDerivedPayload(
                        DerivedArtifactPayloadEntity(
                            derivedArtifactId = artifactId,
                            formatVersion = PAYLOAD_FORMAT_VERSION,
                            content = projection,
                            builtAt = now,
                        ),
                    )
                    check(lifecycleDao.finishDerivedRebuild(
                        artifactId = artifactId,
                        state = DerivedArtifactState.CURRENT,
                        sourceRevision = artifact.sourceRevision + 1L,
                        artifactHash = hash,
                    ) == 1)
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

    private fun buildSearchDocument(artifact: DerivedArtifactEntity): String? {
        val memories = lifecycleDao.artifactMemoryDependencies(artifact.id).mapNotNull { dependency ->
            val memory = memoryDao.memory(dependency.memoryId) ?: return@mapNotNull null
            if (memory.truthState != MemoryTruthState.SUPPORTED ||
                memory.retentionState == MemoryRetentionState.FORGOTTEN ||
                memory.lifecycleState != MemoryLifecycleState.VALIDATED
            ) return@mapNotNull null
            val hasSafeSupport = lifecycleDao.availableEvidence(listOf(memory.id), MAX_EVIDENCE_SCAN)
                .asSequence()
                .filter { it.role.isPositiveMemoryGrounding() }
                .any { evidence ->
                    !isEvidenceSuppressedInCurrentTransaction(
                        maintenanceDao,
                        evidence.experienceId,
                        evidence.lineageKey,
                    )
                }
            memory.takeIf { hasSafeSupport }
        }
        if (memories.isEmpty()) return null
        return buildString {
            append("format=").append(PAYLOAD_FORMAT_VERSION)
            append("\ntype=SEARCH_DOCUMENT")
            memories.sortedBy { it.id }.forEach { memory ->
                append("\nmemory|").append(escaped(memory.id)).append('|')
                    .append(memory.updatedAt).append('|').append(escaped(memory.meaning))
            }
        }
    }

    private fun hasPendingProvenance(artifact: DerivedArtifactEntity): Boolean =
        lifecycleDao.artifactMemoryDependencies(artifact.id).any { dependency ->
            memoryDao.memory(dependency.memoryId)?.let { memory ->
                memory.lifecycleState == MemoryLifecycleState.REASSESSMENT_PENDING &&
                    !memory.hasTerminalLifecycleIntent()
            } == true
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
        const val PAYLOAD_FORMAT_VERSION = 2
        const val TARGET_ARTIFACT = "DERIVED_ARTIFACT"
        const val TARGET_MEMORY = "MEMORY"
        const val TARGET_EXPERIENCE = "EXPERIENCE"
        const val TARGET_MESSAGE = "MESSAGE"
        const val TARGET_OPEN_LOOP = "OPEN_LOOP"
        const val TARGET_ATTACHMENT = "ATTACHMENT"
        const val TARGET_CANDIDATE = "CANDIDATE_MEMORY"
        private const val MAX_EVIDENCE_SCAN = 65
    }
}

class DerivedArtifactRepairHandler(
    override val supportedType: RepairJobType,
    private val service: DerivedArtifactRepairService,
) : RepairJobHandler {
    override suspend fun execute(targetType: String, targetId: String): RepairJobHandlerResult =
        service.repair(targetType, targetId)
}

fun derivedArtifactRepairHandlers(service: DerivedArtifactRepairService): List<RepairJobHandler> =
    listOf(
        RepairJobType.INVALIDATE_DERIVED,
        RepairJobType.REBUILD_DERIVED,
        RepairJobType.PROPAGATE_CORRECTION,
        RepairJobType.PROPAGATE_DELETION,
    ).map { type -> DerivedArtifactRepairHandler(type, service) }
